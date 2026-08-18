"""Orchestrator cho eKYC — gọi OCR, face match, liveness."""

import base64
import logging

from app.ml.ocr_extractor import OcrExtractor
from app.ml.face_matcher import FaceMatcher
from app.ml.liveness_detector import LivenessDetector

logger = logging.getLogger(__name__)


class EkycService:
    """Dịch vụ eKYC tổng hợp."""

    def __init__(self):
        self._ocr = OcrExtractor()
        self._face = FaceMatcher()
        self._liveness = LivenessDetector()

    def ocr(self, image_base64: str) -> dict:
        """OCR trích xuất thông tin từ ảnh CCCD."""
        image_bytes = base64.b64decode(image_base64)
        return self._ocr.extract(image_bytes)

    def face_match(
        self, selfie_base64: str, cccd_image_base64: str, threshold: float = 0.6
    ) -> dict:
        """So khớp khuôn mặt selfie với CCCD."""
        selfie_bytes = base64.b64decode(selfie_base64)
        cccd_bytes = base64.b64decode(cccd_image_base64)
        return self._face.compare(selfie_bytes, cccd_bytes, threshold)

    def liveness(self, image_base64: str) -> dict:
        """Kiểm tra liveness."""
        image_bytes = base64.b64decode(image_base64)
        return self._liveness.check(image_bytes)
