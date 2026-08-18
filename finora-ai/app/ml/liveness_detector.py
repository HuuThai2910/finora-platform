"""Phát hiện liveness (ảnh thật vs ảnh từ màn hình/ảnh in) bằng LBP texture analysis.

Nguyên lý: ảnh chụp thật có texture phong phú (LBP histogram trải đều),
ảnh từ màn hình/ảnh in có texture nghèo (LBP histogram tập trung).
"""

import logging

import numpy as np
from numpy.typing import NDArray

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

# Ngưỡng LBP variance — ảnh thật thường > 600, ảnh giả < 400
LBP_VARIANCE_THRESHOLD = 500.0
METHOD = "lbp_texture"


class LivenessDetector:
    """Kiểm tra liveness bằng phân tích texture LBP."""

    def check(self, image_bytes: bytes) -> dict:
        """Kiểm tra ảnh có phải chụp trực tiếp (live) hay không.

        Args:
            image_bytes: Ảnh dạng bytes (JPEG/PNG).

        Returns:
            dict: {"is_live": bool, "confidence": float, "method": str}
        """
        fail_result = {"is_live": False, "confidence": 0.0, "method": METHOD}

        try:
            image = self._decode(image_bytes)
            if image is None:
                return fail_result

            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            lbp = self._compute_lbp(gray)
            variance = float(np.var(lbp))

            # Normalize variance thành confidence (0-1)
            # variance > threshold → live, confidence cao
            confidence = min(1.0, variance / (LBP_VARIANCE_THRESHOLD * 2))
            is_live = variance > LBP_VARIANCE_THRESHOLD

            logger.info(
                "Liveness check: variance=%.2f threshold=%.2f is_live=%s confidence=%.4f",
                variance, LBP_VARIANCE_THRESHOLD, is_live, confidence,
            )

            return {
                "is_live": is_live,
                "confidence": round(confidence, 4),
                "method": METHOD,
            }

        except Exception:
            logger.exception("Lỗi khi kiểm tra liveness.")
            return fail_result

    @staticmethod
    def _compute_lbp(gray: NDArray) -> NDArray:
        """Tính Local Binary Pattern đơn giản (3x3 neighborhood)."""
        h, w = gray.shape
        lbp = np.zeros((h - 2, w - 2), dtype=np.uint8)

        for i in range(1, h - 1):
            for j in range(1, w - 1):
                center = gray[i, j]
                code = 0
                code |= (gray[i - 1, j - 1] >= center) << 7
                code |= (gray[i - 1, j] >= center) << 6
                code |= (gray[i - 1, j + 1] >= center) << 5
                code |= (gray[i, j + 1] >= center) << 4
                code |= (gray[i + 1, j + 1] >= center) << 3
                code |= (gray[i + 1, j] >= center) << 2
                code |= (gray[i + 1, j - 1] >= center) << 1
                code |= (gray[i, j - 1] >= center) << 0
                lbp[i - 1, j - 1] = code

        return lbp

    @staticmethod
    def _decode(image_bytes: bytes) -> NDArray | None:
        try:
            if cv2 is None:
                raise RuntimeError("opencv-python-headless chưa được cài đặt.")
            nparr = np.frombuffer(image_bytes, np.uint8)
            return cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        except Exception:
            return None
