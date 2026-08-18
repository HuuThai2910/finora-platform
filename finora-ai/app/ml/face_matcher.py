"""So khớp khuôn mặt selfie với ảnh CCCD sử dụng DeepFace."""

import logging
import tempfile

import numpy as np

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

try:
    from deepface import DeepFace
except ImportError:
    DeepFace = None  # type: ignore[assignment,misc]

logger = logging.getLogger(__name__)

DEFAULT_THRESHOLD = 0.6
MODEL_NAME = "VGG-Face"
DISTANCE_METRIC = "cosine"


class FaceMatcher:
    """So khớp khuôn mặt giữa ảnh selfie và ảnh trên CCCD."""

    def compare(
        self,
        selfie_bytes: bytes,
        cccd_bytes: bytes,
        threshold: float = DEFAULT_THRESHOLD,
    ) -> dict:
        """So khớp 2 ảnh khuôn mặt.

        Args:
            selfie_bytes: Ảnh selfie dạng bytes (JPEG/PNG).
            cccd_bytes: Ảnh CCCD dạng bytes (JPEG/PNG).
            threshold: Ngưỡng similarity để coi là match (0-1).

        Returns:
            dict: {"match": bool, "similarity": float, "threshold": float}
        """
        fail_result = {"match": False, "similarity": 0.0, "threshold": threshold}

        try:
            selfie_path = self._save_temp(selfie_bytes, "selfie")
            cccd_path = self._save_temp(cccd_bytes, "cccd")

            if selfie_path is None or cccd_path is None:
                logger.warning("Không thể giải mã ảnh đầu vào.")
                return fail_result

            if DeepFace is None:
                raise RuntimeError("deepface chưa được cài đặt.")

            result = DeepFace.verify(
                img1_path=selfie_path,
                img2_path=cccd_path,
                model_name=MODEL_NAME,
                distance_metric=DISTANCE_METRIC,
                enforce_detection=False,
            )

            distance = result.get("distance", 1.0)
            # Cosine distance → similarity: similarity = 1 - distance
            similarity = round(max(0.0, min(1.0, 1.0 - distance)), 4)
            is_match = similarity >= threshold

            logger.info(
                "Face match: similarity=%.4f threshold=%.2f match=%s",
                similarity, threshold, is_match,
            )

            return {
                "match": is_match,
                "similarity": similarity,
                "threshold": threshold,
            }

        except Exception:
            logger.exception("Lỗi khi so khớp khuôn mặt.")
            return fail_result

    @staticmethod
    def _save_temp(image_bytes: bytes, prefix: str) -> str | None:
        """Lưu bytes thành file tạm để DeepFace đọc."""
        try:
            if cv2 is None:
                raise RuntimeError("opencv-python-headless chưa được cài đặt.")
            nparr = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
            if image is None:
                return None
            tmp = tempfile.NamedTemporaryFile(
                suffix=".jpg", prefix=f"ekyc_{prefix}_", delete=False
            )
            cv2.imwrite(tmp.name, image)
            return tmp.name
        except Exception:
            return None
