"""Dò mặt (YuNet 2023mar) và trích embedding (SFace 2021dec) bằng OpenCV.

Chọn hai model này vì chỉ cần OpenCV (không torch/TensorFlow), nhỏ, chạy CPU — vừa
container ``ai`` ~700 MB trên VM 2 vCPU. Model nạp **lười** ở lần dùng đầu và dùng
chung; ``FaceDetectorYN`` có trạng thái (``setInputSize``) nên mọi lời gọi đi qua khoá.

Quy ước điểm mốc YuNet: ``right_eye`` là mắt phải **của người trong ảnh** (nằm bên trái
ảnh nếu ảnh không lật gương). Các công thức ở module liveness dùng trị tuyệt đối khoảng
cách hai mắt nên không phụ thuộc nhãn này.

Dữ liệu khuôn mặt là dữ liệu sinh trắc học (nhạy cảm): module không log, không lưu.
"""

from __future__ import annotations

import base64
import binascii
import logging
import threading
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from app.ml.ekyc import image_io
from app.ml.ekyc.thresholds import DocThresholds, settings

logger = logging.getLogger(__name__)

YUNET_FILE = "face_detection_yunet_2023mar.onnx"
SFACE_FILE = "face_recognition_sface_2021dec.onnx"
DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[3] / "models" / "face"
EMBEDDING_DIM = 128

REASON_NO_FACE = "NO_FACE"
REASON_MULTIPLE_FACES = "MULTIPLE_FACES"
REASON_FACE_TOO_SMALL = "FACE_TOO_SMALL"
REASON_BLURRY = "BLURRY"


class FaceModelsMissing(RuntimeError):
    """Thiếu file model YuNet/SFace — endpoint trả 503 ``FACE_MODELS_MISSING``."""


Point = tuple[float, float]


@dataclass(frozen=True)
class Face:
    """Một khuôn mặt YuNet: hộp bao, 5 điểm mốc và điểm tin cậy."""

    x: float
    y: float
    w: float
    h: float
    right_eye: Point
    left_eye: Point
    nose: Point
    mouth_right: Point
    mouth_left: Point
    score: float

    @property
    def ied(self) -> float:
        """Khoảng cách giữa hai mắt (px)."""
        return float(
            np.hypot(
                self.left_eye[0] - self.right_eye[0],
                self.left_eye[1] - self.right_eye[1],
            )
        )

    @property
    def area(self) -> float:
        return self.w * self.h

    def scaled(self, factor: float) -> Face:
        """Đổi toạ độ sang ảnh lớn/nhỏ hơn ``factor`` lần."""

        def sp(p: Point) -> Point:
            return (p[0] * factor, p[1] * factor)

        return Face(
            self.x * factor,
            self.y * factor,
            self.w * factor,
            self.h * factor,
            sp(self.right_eye),
            sp(self.left_eye),
            sp(self.nose),
            sp(self.mouth_right),
            sp(self.mouth_left),
            self.score,
        )

    def as_row(self) -> NDArray[np.float32]:
        """Hàng 15 số đúng khuôn YuNet để ``FaceRecognizerSF.alignCrop`` dùng."""
        return np.array(
            [
                self.x,
                self.y,
                self.w,
                self.h,
                *self.right_eye,
                *self.left_eye,
                *self.nose,
                *self.mouth_right,
                *self.mouth_left,
                self.score,
            ],
            dtype=np.float32,
        )

    @staticmethod
    def from_row(row: NDArray) -> Face:
        values = [float(v) for v in np.asarray(row).reshape(-1)[:15]]
        return Face(
            values[0],
            values[1],
            values[2],
            values[3],
            (values[4], values[5]),
            (values[6], values[7]),
            (values[8], values[9]),
            (values[10], values[11]),
            (values[12], values[13]),
            values[14],
        )


def model_dir() -> Path:
    """Thư mục model: ``EKYC_FACE_MODEL_DIR`` hoặc ``finora-ai/models/face``."""
    configured = settings().face_model_dir
    return Path(configured) if configured else DEFAULT_MODEL_DIR


def models_present(directory: Path | None = None) -> bool:
    """Đủ hai file model (không rỗng)."""
    directory = directory or model_dir()
    return all(
        (directory / name).is_file() and (directory / name).stat().st_size > 0
        for name in (YUNET_FILE, SFACE_FILE)
    )


class FaceEngine:
    """Bọc YuNet + SFace; an toàn khi gọi từ nhiều luồng nhờ khoá chung."""

    def __init__(self, directory: Path):
        if not models_present(directory):
            raise FaceModelsMissing(str(directory))
        try:
            self._detector = cv2.FaceDetectorYN.create(
                str(directory / YUNET_FILE), "", (320, 320), 0.5, 0.3, 50
            )
            self._recognizer = cv2.FaceRecognizerSF.create(
                str(directory / SFACE_FILE), ""
            )
        except cv2.error as exc:
            raise FaceModelsMissing(f"Không nạp được model: {exc}") from exc
        self._lock = threading.Lock()

    def detect(self, image: NDArray, score_threshold: float) -> list[Face]:
        """Mọi khuôn mặt có điểm ≥ ngưỡng, mặt lớn nhất đứng đầu."""
        h, w = image.shape[:2]
        if h < 10 or w < 10:
            return []
        with self._lock:
            self._detector.setInputSize((w, h))
            self._detector.setScoreThreshold(float(score_threshold))
            _, rows = self._detector.detect(np.ascontiguousarray(image))
        if rows is None:
            return []
        faces = [Face.from_row(row) for row in rows]
        return sorted(faces, key=lambda f: f.area, reverse=True)

    def align(self, image: NDArray, face: Face) -> NDArray:
        """Crop 112×112 đã căn theo 5 điểm mốc (đầu vào chuẩn của SFace)."""
        with self._lock:
            return self._recognizer.alignCrop(
                np.ascontiguousarray(image), face.as_row()
            )

    def embed(self, image: NDArray, face: Face) -> NDArray[np.float32]:
        """Embedding SFace 128 chiều đã chuẩn hoá L2."""
        aligned = self.align(image, face)
        with self._lock:
            feature = self._recognizer.feature(aligned)
        return normalize(np.asarray(feature, dtype=np.float32).reshape(-1))

    def face_sharpness(self, image: NDArray, face: Face) -> float:
        """Độ nét đo trên crop đã căn 112×112 — cùng thang đo cho mọi độ phân giải."""
        return image_io.sharpness(self.align(image, face))


_engine: FaceEngine | None = None
_engine_lock = threading.Lock()


def get_engine() -> FaceEngine:
    """Engine dùng chung; thiếu model thì ném ``FaceModelsMissing``."""
    global _engine
    if _engine is None:
        with _engine_lock:
            if _engine is None:
                directory = model_dir()
                logger.info("Nạp model khuôn mặt YuNet/SFace từ %s", directory)
                _engine = FaceEngine(directory)
    return _engine


def ensure_models() -> None:
    """Kiểm nhanh (không nạp) để router trả 503 sớm khi thiếu model."""
    if _engine is None and not models_present():
        raise FaceModelsMissing(str(model_dir()))


def normalize(vector: NDArray) -> NDArray[np.float32]:
    vector = np.asarray(vector, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(vector))
    return vector / norm if norm > 0 else vector


def cosine(a: NDArray, b: NDArray) -> float:
    """Cosine giữa hai embedding (đã hay chưa chuẩn hoá đều được)."""
    return float(np.dot(normalize(a), normalize(b)))


def encode_embedding(vector: NDArray) -> str:
    """128 float32 little-endian, chuẩn hoá L2 → base64 (khuôn ``embedding_b64``)."""
    return base64.b64encode(normalize(vector).astype("<f4").tobytes()).decode("ascii")


def decode_embedding(text: str) -> NDArray[np.float32]:
    """Ngược của ``encode_embedding``; sai khuôn thì ``ValueError``."""
    try:
        raw = base64.b64decode(text.strip(), validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("EMBEDDING_BASE64_INVALID") from exc
    if len(raw) != EMBEDDING_DIM * 4:
        raise ValueError("EMBEDDING_SIZE_INVALID")
    vector = np.frombuffer(raw, dtype="<f4").astype(np.float32)
    if not np.all(np.isfinite(vector)) or float(np.linalg.norm(vector)) == 0.0:
        raise ValueError("EMBEDDING_VALUE_INVALID")
    return normalize(vector)


@dataclass(frozen=True)
class PortraitResult:
    """Chân dung trên mặt trước thẻ; ``embedding`` chỉ có khi ``found``."""

    found: bool
    reason: str | None
    faces: int
    ied_px: float | None = None
    sharpness: float | None = None
    embedding: NDArray[np.float32] | None = None
    face: Face | None = None


def extract_portrait(
    engine: FaceEngine, card: NDArray, th: DocThresholds
) -> PortraitResult:
    """Tìm đúng **một** khuôn mặt ở phần trái mặt trước rồi trích embedding.

    Kiểm theo thứ tự: số mặt (đúng 1) → IED → độ nét. Chân dung mờ/nhỏ cho embedding
    kém tin cậy theo **cả hai chiều** (từ chối nhầm và nhận nhầm), nên không dùng.
    """
    region = card[:, : max(1, int(card.shape[1] * th.portrait_region_ratio))]
    faces = engine.detect(region, th.portrait_min_score)
    if not faces:
        return PortraitResult(found=False, reason=REASON_NO_FACE, faces=0)
    if len(faces) > 1:
        return PortraitResult(
            found=False, reason=REASON_MULTIPLE_FACES, faces=len(faces)
        )
    face = faces[0]
    if face.ied < th.portrait_min_ied:
        return PortraitResult(
            found=False,
            reason=REASON_FACE_TOO_SMALL,
            faces=1,
            ied_px=face.ied,
            face=face,
        )
    sharp = engine.face_sharpness(region, face)
    if sharp < th.portrait_min_sharpness:
        return PortraitResult(
            found=False,
            reason=REASON_BLURRY,
            faces=1,
            ied_px=face.ied,
            sharpness=sharp,
            face=face,
        )
    return PortraitResult(
        found=True,
        reason=None,
        faces=1,
        ied_px=face.ied,
        sharpness=sharp,
        embedding=engine.embed(region, face),
        face=face,
    )


def any_face(engine: FaceEngine, image: NDArray, score_threshold: float = 0.6) -> bool:
    """Ảnh có khuôn mặt nào không — dùng phát hiện chụp nhầm mặt thẻ."""
    return bool(engine.detect(image, score_threshold))


def mask_faces(
    engine: FaceEngine, image: NDArray, score_threshold: float = 0.5
) -> NDArray:
    """Tô đen mọi khuôn mặt dò được (nới 25%) — lớp chắn cuối trước khi gửi ảnh cho AI."""
    masked = image.copy()
    height, width = masked.shape[:2]
    for face in engine.detect(masked, score_threshold):
        pad_x, pad_y = face.w * 0.25, face.h * 0.25
        x0 = max(0, int(face.x - pad_x))
        y0 = max(0, int(face.y - pad_y))
        x1 = min(width, int(face.x + face.w + pad_x))
        y1 = min(height, int(face.y + face.h + pad_y))
        masked[y0:y1, x0:x1] = 0
    return masked
