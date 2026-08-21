"""Tiện ích ảnh dùng chung cho eKYC — giải mã bytes và chuẩn hoá kích thước."""

import base64
import logging

import numpy as np
from numpy.typing import NDArray

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)


def require_cv2() -> None:
    if cv2 is None:
        raise RuntimeError("opencv-python-headless chưa được cài đặt.")


def decode_image(image_bytes: bytes) -> NDArray | None:
    """Giải mã bytes ảnh (JPEG/PNG) thành mảng BGR. Trả ``None`` nếu hỏng."""
    try:
        require_cv2()
        nparr = np.frombuffer(image_bytes, np.uint8)
        return cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    except Exception:  # noqa: BLE001 — ảnh hỏng dưới mọi dạng đều quy về None
        return None


def decode_base64_image(image_base64: str) -> NDArray | None:
    """Giải mã chuỗi base64 thành mảng BGR. Trả ``None`` nếu base64 hỏng."""
    try:
        return decode_image(base64.b64decode(image_base64, validate=False))
    except Exception:  # noqa: BLE001 — base64 hỏng dưới mọi dạng đều quy về None
        logger.warning("Chuỗi base64 không hợp lệ.")
        return None


def resize_to_width(image: NDArray, width: int) -> NDArray:
    """Thu nhỏ ảnh về chiều rộng cố định (chỉ thu, không phóng to)."""
    require_cv2()
    h, w = image.shape[:2]
    if w <= width:
        return image
    scale = width / w
    return cv2.resize(
        image, (width, max(1, int(h * scale))), interpolation=cv2.INTER_AREA
    )


def sharpness(image: NDArray) -> float:
    """Độ nét theo variance của Laplacian — càng cao càng nét."""
    require_cv2()
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())
