"""Router eKYC — OCR CCCD, face matching, liveness detection."""

import logging
from functools import lru_cache

from fastapi import APIRouter, HTTPException, status

from app.schemas.ekyc import (
    ActiveLivenessRequest,
    ActiveLivenessResponse,
    FaceMatchRequest,
    FaceMatchResponse,
    LivenessRequest,
    LivenessResponse,
    OcrRequest,
    OcrResponse,
)
from app.services.ekyc.service import EkycService

logger = logging.getLogger(__name__)
router = APIRouter()


@lru_cache(maxsize=1)
def get_ekyc_service() -> EkycService:
    """Singleton eKYC service."""
    logger.info("Khởi tạo EkycService...")
    return EkycService()


@router.post("/ocr", response_model=OcrResponse)
async def ocr_cccd(request: OcrRequest):
    """Trích xuất thông tin từ ảnh CCCD bằng OCR.

    Luồng: Ảnh base64 → EasyOCR → regex extract → thông tin CCCD
    """
    service = get_ekyc_service()
    result = service.ocr(request.image_base64)
    return OcrResponse(**result)


@router.post("/face-match", response_model=FaceMatchResponse)
async def face_match(request: FaceMatchRequest):
    """So khớp khuôn mặt selfie với ảnh trên CCCD.

    Luồng: 2 ảnh base64 → DeepFace verify → similarity score → match/no match
    """
    service = get_ekyc_service()
    result = service.face_match(request.selfie_base64, request.cccd_image_base64)
    return FaceMatchResponse(**result)


@router.post("/liveness", response_model=LivenessResponse)
async def check_liveness(request: LivenessRequest):
    """Kiểm tra ảnh có phải chụp trực tiếp (live) hay từ màn hình/ảnh in.

    Luồng: Ảnh base64 → LBP texture analysis → variance check → live/fake
    """
    service = get_ekyc_service()
    result = service.liveness(request.image_base64)
    return LivenessResponse(**result)


@router.post("/liveness-active", response_model=ActiveLivenessResponse)
async def check_active_liveness(request: ActiveLivenessRequest):
    """Kiểm tra người dùng thực hiện đúng chuỗi hành động của phiên challenge.

    Luồng: nhiều frame base64 → MediaPipe FaceMesh (EAR + yaw) → khớp đúng
    hành động và đúng thứ tự → lớp phụ LBP trên frame tốt nhất → live/fake.

    Chuỗi hành động do ``finora-user`` sinh ngẫu nhiên cho từng phiên; service
    này không giữ trạng thái phiên, chỉ kiểm chứng bằng chứng kỹ thuật.
    """
    service = get_ekyc_service()
    try:
        result = service.active_liveness(request.frames, request.expected_actions)
    except ValueError as e:
        # Hành động không hỗ trợ là lỗi hợp đồng phía gọi, không phải lỗi hệ thống
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e)) from e
    return ActiveLivenessResponse(**result)
