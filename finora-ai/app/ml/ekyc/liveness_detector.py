"""Passive liveness — phân biệt ảnh chụp trực tiếp với ảnh chụp lại từ màn hình/ảnh in.

Nguyên lý: ảnh chụp thật có texture phong phú (LBP histogram trải đều),
ảnh chụp lại từ màn hình/ảnh in có texture nghèo (LBP histogram tập trung).

Dùng ở hai chỗ:
  * endpoint ``/liveness`` — kiểm tra nhanh một ảnh tĩnh;
  * lớp phụ trong active liveness — chạy trên vùng khuôn mặt của frame tốt nhất.
"""

import logging

import numpy as np
from numpy.typing import NDArray

from app.ml.ekyc.image_io import decode_image, require_cv2, resize_to_width
from app.ml.ekyc.thresholds import LBP_RESIZE_WIDTH, LBP_VARIANCE_THRESHOLD

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

METHOD = "lbp_texture"

#: Vùng khuôn mặt dạng (x, y, w, h) theo toạ độ ảnh gốc.
Bbox = tuple[int, int, int, int]


class LivenessDetector:
    """Kiểm tra liveness bằng phân tích texture LBP."""

    def check(self, image_bytes: bytes, roi: Bbox | None = None) -> dict:
        """Kiểm tra ảnh có phải chụp trực tiếp (live) hay không.

        Args:
            image_bytes: Ảnh dạng bytes (JPEG/PNG).
            roi: Vùng khuôn mặt nếu đã biết — chỉ xét vùng này thay vì cả ảnh,
                vừa nhanh hơn vừa bớt nhiễu từ hậu cảnh.

        Returns:
            dict: {"is_live": bool, "confidence": float, "method": str}
        """
        image = decode_image(image_bytes)
        if image is None:
            logger.warning("Không thể giải mã ảnh khi kiểm tra liveness.")
            return {"is_live": False, "confidence": 0.0, "method": METHOD}
        return self.check_image(image, roi)

    def check_image(self, image: NDArray, roi: Bbox | None = None) -> dict:
        """Như :meth:`check` nhưng nhận sẵn ảnh BGR đã giải mã."""
        fail_result = {"is_live": False, "confidence": 0.0, "method": METHOD}

        try:
            variance = self.texture_variance(image, roi)
            if variance is None:
                return fail_result

            # Chuẩn hoá variance thành confidence (0-1):
            # variance vượt ngưỡng → live, confidence cao
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

    def texture_variance(self, image: NDArray, roi: Bbox | None = None) -> float | None:
        """Variance của ảnh LBP trên vùng quan tâm.

        Trả ``None`` nếu vùng ảnh quá nhỏ để tính LBP.
        """
        require_cv2()

        patch = self._crop(image, roi)
        if patch is None:
            return None

        patch = resize_to_width(patch, LBP_RESIZE_WIDTH)
        gray = cv2.cvtColor(patch, cv2.COLOR_BGR2GRAY) if patch.ndim == 3 else patch
        if gray.shape[0] < 3 or gray.shape[1] < 3:
            logger.warning("Vùng ảnh quá nhỏ để tính LBP: shape=%s", gray.shape)
            return None

        return float(np.var(self._compute_lbp(gray)))

    @staticmethod
    def _compute_lbp(gray: NDArray) -> NDArray:
        """Local Binary Pattern 3x3, vector hoá bằng numpy.

        Tương đương vòng lặp từng pixel nhưng nhanh hơn hàng trăm lần: mỗi bit
        của mã LBP là một phép so sánh trên toàn mảng đã dịch chỉ số.
        Thứ tự bit giữ nguyên (bắt đầu từ góc trên-trái, đi theo chiều kim đồng hồ).
        """
        center = gray[1:-1, 1:-1]

        def bit(neighbor: NDArray, shift: int) -> NDArray:
            return (neighbor >= center).astype(np.uint8) << shift

        return (
            bit(gray[:-2, :-2], 7)
            | bit(gray[:-2, 1:-1], 6)
            | bit(gray[:-2, 2:], 5)
            | bit(gray[1:-1, 2:], 4)
            | bit(gray[2:, 2:], 3)
            | bit(gray[2:, 1:-1], 2)
            | bit(gray[2:, :-2], 1)
            | bit(gray[1:-1, :-2], 0)
        )

    @staticmethod
    def _crop(image: NDArray, roi: Bbox | None) -> NDArray | None:
        """Cắt vùng khuôn mặt, tự kẹp về trong biên ảnh."""
        if roi is None:
            return image

        h, w = image.shape[:2]
        x, y, bw, bh = roi
        x0, y0 = max(0, int(x)), max(0, int(y))
        x1, y1 = min(w, int(x + bw)), min(h, int(y + bh))
        if x1 - x0 < 3 or y1 - y0 < 3:
            return None
        return image[y0:y1, x0:x1]
