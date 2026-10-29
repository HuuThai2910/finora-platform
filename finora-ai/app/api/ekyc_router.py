"""Router eKYC **nội bộ**: chỉ ``finora-user`` gọi, gateway chặn từ bên ngoài.

- Quyền gắn ở ``main.py`` (D35): access token service account có client role ``ai:ekyc:ocr``
  (``finora-user-client``, D34); thiếu/sai token 401, thiếu quyền 403.
- Handler là ``def`` (không ``async``) để FastAPI chạy trong threadpool — phân tích ảnh
  vài giây không làm đứng ``/credit``, ``/fraud``, ``/health``.
- Chỉ **một** phân tích nặng tại một thời điểm (VM 2 vCPU, container ~700 MB): bận thì
  trả ngay ``503 BUSY`` kèm ``Retry-After: 3`` thay vì xếp hàng làm phình RAM.
- Thứ tự kiểm: đầu vào (422) → model mặt (503 ``FACE_MODELS_MISSING``) → slot (503 ``BUSY``).
"""

import logging
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from functools import lru_cache
from typing import Annotated

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from app.ml.ekyc import face, image_io
from app.ml.ekyc.face import FaceModelsMissing
from app.ml.ekyc.image_io import ImageInputError
from app.schemas.ekyc import (
    MAX_STILL_FRAME_BYTES,
    MAX_STILL_FRAMES,
    MAX_VIDEO_BYTES,
    DocumentRequest,
    DocumentResponse,
    LivenessResponse,
    LivenessSpec,
)
from app.services.ekyc.service import EkycService

logger = logging.getLogger(__name__)

RETRY_AFTER_SECONDS = "3"

_heavy_slot = threading.Semaphore(1)

# Quyền (client role ai:ekyc:ocr) gắn khi include router trong main.py, giống các router khác của AI (D35).
router = APIRouter()


@lru_cache(maxsize=1)
def get_ekyc_service() -> EkycService:
    """Service dùng chung (nạp cấu hình + Gemini một lần)."""
    logger.info("Khởi tạo EkycService...")
    return EkycService()


@contextmanager
def heavy_slot() -> Iterator[None]:
    """Giữ slot phân tích nặng duy nhất; không chờ — bận thì 503 ngay."""
    if not _heavy_slot.acquire(blocking=False):
        raise HTTPException(
            status_code=503, detail="BUSY", headers={"Retry-After": RETRY_AFTER_SECONDS}
        )
    try:
        yield
    finally:
        _heavy_slot.release()


def _ensure_face_models() -> None:
    try:
        face.ensure_models()
    except FaceModelsMissing:
        logger.error("Thiếu model YuNet/SFace — chạy scripts/download_face_models.py.")
        raise HTTPException(status_code=503, detail="FACE_MODELS_MISSING") from None


def _image_bytes(payload: str) -> bytes:
    try:
        data = image_io.decode_base64_strict(payload)
        image_io.validate_image_bytes(data)
    except ImageInputError as exc:
        raise HTTPException(status_code=422, detail=exc.code) from None
    return data


def _document_json(result: dict) -> JSONResponse:
    """Kiểm khuôn hợp đồng rồi trả JSON; ``portrait.embedding_b64`` chỉ có khi ``found``."""
    body = DocumentResponse.model_validate(result).model_dump(mode="json")
    if body["portrait"].get("embedding_b64") is None:
        body["portrait"].pop("embedding_b64", None)
    return JSONResponse(content=body)


@router.post("/document", response_model=DocumentResponse)
def analyze_document(request: DocumentRequest):
    """Đọc CCCD hai mặt bằng nguồn tất định (QR, MRZ, cấu trúc số); AI chỉ điền chỗ trống."""
    front = _image_bytes(request.front_image_base64)
    back = _image_bytes(request.back_image_base64)
    _ensure_face_models()
    with heavy_slot():
        try:
            result = get_ekyc_service().analyze_document(request, front, back)
        except ImageInputError as exc:
            raise HTTPException(status_code=422, detail=exc.code) from None
        except FaceModelsMissing:
            raise HTTPException(status_code=503, detail="FACE_MODELS_MISSING") from None
    return _document_json(result)


def _read_limited(upload: UploadFile, limit: int, code: str) -> bytes:
    data = upload.file.read(limit + 1)
    if len(data) > limit:
        raise HTTPException(status_code=422, detail=code)
    return data


@router.post("/liveness", response_model=LivenessResponse)
def analyze_liveness(
    spec: Annotated[str, Form(max_length=8192)],
    video: Annotated[UploadFile | None, File()] = None,
    # FastAPI 0.109 chỉ gom part lặp thành list khi kiểu là list trần (không bọc Optional);
    # không gửi part nào thì nhận mặc định None.
    frames: Annotated[list[UploadFile], File()] = None,  # noqa: RUF013
):
    """Kiểm tra người thật theo thử thách + so mặt với chân dung CCCD (multipart)."""
    try:
        parsed = LivenessSpec.model_validate_json(spec)
    except ValidationError:
        raise HTTPException(status_code=422, detail="SPEC_INVALID") from None
    try:
        portrait = face.decode_embedding(parsed.portrait_embedding_b64)
    except ValueError:
        raise HTTPException(
            status_code=422, detail="PORTRAIT_EMBEDDING_INVALID"
        ) from None

    video_bytes: bytes | None = None
    frame_bytes: list[bytes] | None = None
    if parsed.capture_mode == "VIDEO":
        if video is None:
            raise HTTPException(status_code=422, detail="VIDEO_MISSING")
        video_bytes = _read_limited(video, MAX_VIDEO_BYTES, "VIDEO_TOO_LARGE")
        if not video_bytes:
            raise HTTPException(status_code=422, detail="VIDEO_MISSING")
    else:
        uploads = frames or []
        if not uploads or len(uploads) > MAX_STILL_FRAMES:
            raise HTTPException(status_code=422, detail="FRAMES_INVALID")
        if len(uploads) != len(parsed.frame_times_ms or []):
            raise HTTPException(status_code=422, detail="FRAME_TIMES_MISMATCH")
        frame_bytes = [
            _read_limited(f, MAX_STILL_FRAME_BYTES, "FRAME_TOO_LARGE") for f in uploads
        ]
        for data in frame_bytes:
            if not data.startswith(image_io.JPEG_MAGIC):
                raise HTTPException(status_code=422, detail="FRAME_NOT_JPEG")

    _ensure_face_models()
    with heavy_slot():
        try:
            result = get_ekyc_service().analyze_liveness(
                parsed, portrait, video_bytes, frame_bytes
            )
        except ImageInputError as exc:
            raise HTTPException(status_code=422, detail=exc.code) from None
        except FaceModelsMissing:
            raise HTTPException(status_code=503, detail="FACE_MODELS_MISSING") from None
    return JSONResponse(
        content=LivenessResponse.model_validate(result).model_dump(mode="json")
    )
