"""Orchestrator cho eKYC — gọi OCR, face match, liveness (thụ động và chủ động)."""

import base64
import logging

from app.ml.ekyc.active_liveness import ActiveLivenessDetector
from app.ml.ekyc.face_matcher import FaceMatcher
from app.ml.ekyc.image_io import decode_base64_image
from app.ml.ekyc.liveness_detector import LivenessDetector
from app.ml.ekyc.ocr_extractor import OcrExtractor

logger = logging.getLogger(__name__)


class EkycService:
    """Dịch vụ eKYC tổng hợp.

    Chỉ trả bằng chứng kỹ thuật (OCR, similarity, liveness). Quyết định nghiệp
    vụ ``VERIFIED``/``MANUAL_REVIEW`` thuộc về ``finora-user`` theo F01.
    """

    def __init__(self):
        self._ocr = OcrExtractor()
        self._face = FaceMatcher()
        self._liveness = LivenessDetector()
        self._active_liveness = ActiveLivenessDetector()

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
        """Kiểm tra liveness thụ động trên một ảnh."""
        image_bytes = base64.b64decode(image_base64)
        return self._liveness.check(image_bytes)

    def active_liveness(self, frames_base64: list[str], expected_actions: list[str]) -> dict:
        """Kiểm tra chuỗi hành động của phiên challenge trên nhiều frame.

        Hai lớp phải cùng đạt thì mới kết luận là người thật:

        1. **Chủ động** — đúng hành động, đúng thứ tự (chặn video quay sẵn).
        2. **Thụ động** — texture LBP trên vùng mặt của frame tốt nhất (chặn
           trường hợp phát video/ảnh từ màn hình mà vẫn có chuyển động).

        Frame giải mã hỏng bị loại bỏ nhưng vẫn giữ nguyên thứ tự thời gian
        của các frame còn lại.
        """
        images = [img for img in map(decode_base64_image, frames_base64) if img is not None]
        broken = len(frames_base64) - len(images)
        if broken:
            logger.warning("Bỏ qua %d/%d frame không giải mã được.", broken, len(frames_base64))

        if not images:
            return {
                "is_live": False,
                "actions": [
                    {"action": a, "passed": False, "evidence": "không giải mã được frame nào"}
                    for a in expected_actions
                ],
                "confidence": 0.0,
                "method": "none",
                "best_frame_index": None,
                "passive_check": None,
            }

        result = self._active_liveness.analyze(images, expected_actions)
        best_index = result.pop("best_frame_index", None)
        best_bbox = result.pop("best_frame_bbox", None)

        passive = None
        if best_index is not None:
            passive = self._liveness.check_image(images[best_index], best_bbox)
            if not passive["is_live"]:
                logger.info(
                    "Active liveness đạt hành động nhưng trượt lớp texture: confidence=%.4f",
                    passive["confidence"],
                )

        is_live = bool(result["is_live"] and passive and passive["is_live"])
        confidence = (
            min(result["confidence"], passive["confidence"]) if passive else result["confidence"]
        )

        return {
            **result,
            "is_live": is_live,
            "confidence": round(confidence, 4),
            "best_frame_index": best_index,
            "passive_check": passive,
        }
