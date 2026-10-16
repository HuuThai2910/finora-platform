"""Đầu vào ảnh cho eKYC: kiểm base64, magic bytes, kích thước rồi mới giải mã.

Ảnh do client gửi lên là dữ liệu không tin cậy, nên thứ tự kiểm cố ý từ rẻ đến đắt:
độ dài chuỗi → base64 chặt → magic bytes JPEG/PNG → kích thước khai trong header (chặn
"bom giải nén" — file nhỏ nhưng khai 20000×20000 px) → mới gọi ``cv2.imdecode``.
Sau khi giải mã, mọi bước xử lý chỉ làm việc trên mảng pixel; byte gốc (EXIF, metadata
lạ) không đi tiếp vào thư viện nào khác. ``cv2.imdecode`` với ``IMREAD_COLOR`` tự xoay
theo EXIF, nên ảnh điện thoại chụp dọc vẫn đúng chiều.
"""

from __future__ import annotations

import base64
import binascii
import re
import struct

import cv2
import numpy as np
from numpy.typing import NDArray

#: Hợp đồng API: mỗi ảnh base64 tối đa 6.000.000 ký tự (~4,5 MB).
MAX_IMAGE_B64_CHARS = 6_000_000
#: Ảnh khai trong header lớn hơn mức này bị từ chối trước khi giải mã.
MAX_IMAGE_PIXELS = 40_000_000

JPEG_MAGIC = b"\xff\xd8\xff"
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"

_DATA_URL_PREFIX = re.compile(r"^data:image/[a-zA-Z0-9.+-]+;base64,")
_WHITESPACE = re.compile(r"\s+")
# Marker SOF của JPEG (baseline, progressive, lossless…) mang chiều cao/rộng ảnh.
_JPEG_SOF_MARKERS = {
    0xC0,
    0xC1,
    0xC2,
    0xC3,
    0xC5,
    0xC6,
    0xC7,
    0xC9,
    0xCA,
    0xCB,
    0xCD,
    0xCE,
    0xCF,
}


class ImageInputError(ValueError):
    """Ảnh đầu vào không hợp lệ; ``code`` là mã lỗi trả về trong HTTP 422."""

    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


def decode_base64_strict(text: str, max_chars: int = MAX_IMAGE_B64_CHARS) -> bytes:
    """Giải base64 chặt: chỉ bỏ tiền tố data URL và khoảng trắng, còn lại phải hợp lệ."""
    if len(text) > max_chars:
        raise ImageInputError("IMAGE_TOO_LARGE")
    payload = _WHITESPACE.sub("", _DATA_URL_PREFIX.sub("", text.strip()))
    if not payload:
        raise ImageInputError("IMAGE_BASE64_INVALID")
    try:
        return base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ImageInputError("IMAGE_BASE64_INVALID") from exc


def sniff_image_type(data: bytes) -> str | None:
    """``jpeg`` | ``png`` theo magic bytes; định dạng khác trả ``None``."""
    if data.startswith(JPEG_MAGIC):
        return "jpeg"
    if data.startswith(PNG_MAGIC):
        return "png"
    return None


def _png_dimensions(data: bytes) -> tuple[int, int] | None:
    # IHDR luôn là chunk đầu tiên: 8 byte chữ ký + 4 độ dài + 4 "IHDR" + rộng + cao.
    if len(data) < 24 or data[12:16] != b"IHDR":
        return None
    width, height = struct.unpack(">II", data[16:24])
    return width, height


def _jpeg_dimensions(data: bytes) -> tuple[int, int] | None:
    pos = 2
    size = len(data)
    while pos + 4 <= size:
        if data[pos] != 0xFF:
            return None
        marker = data[pos + 1]
        if marker == 0xFF:  # byte đệm giữa các marker
            pos += 1
            continue
        if marker in (0x01, 0xD8) or 0xD0 <= marker <= 0xD7:
            pos += 2
            continue
        if marker in (0xD9, 0xDA):  # hết ảnh / bắt đầu dữ liệu quét mà chưa thấy SOF
            return None
        (length,) = struct.unpack(">H", data[pos + 2 : pos + 4])
        if marker in _JPEG_SOF_MARKERS:
            if pos + 9 > size:
                return None
            height, width = struct.unpack(">HH", data[pos + 5 : pos + 9])
            return width, height
        pos += 2 + length
    return None


def image_dimensions(data: bytes) -> tuple[int, int] | None:
    """Kích thước (rộng, cao) đọc từ header, không giải mã pixel."""
    kind = sniff_image_type(data)
    if kind == "png":
        return _png_dimensions(data)
    if kind == "jpeg":
        return _jpeg_dimensions(data)
    return None


def validate_image_bytes(data: bytes, max_pixels: int = MAX_IMAGE_PIXELS) -> None:
    """Kiểm magic bytes + kích thước header; ném ``ImageInputError`` nếu không đạt."""
    if sniff_image_type(data) is None:
        raise ImageInputError("IMAGE_TYPE_UNSUPPORTED")
    dims = image_dimensions(data)
    if dims is None:
        raise ImageInputError("IMAGE_DECODE_FAILED")
    width, height = dims
    if width <= 0 or height <= 0:
        raise ImageInputError("IMAGE_DECODE_FAILED")
    if width * height > max_pixels:
        raise ImageInputError("IMAGE_TOO_LARGE")


def decode_image_bytes(
    data: bytes, max_pixels: int = MAX_IMAGE_PIXELS
) -> NDArray[np.uint8]:
    """Kiểm rồi giải mã JPEG/PNG thành ảnh BGR ``uint8``."""
    validate_image_bytes(data, max_pixels)
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None or image.size == 0:
        raise ImageInputError("IMAGE_DECODE_FAILED")
    return image


def decode_base64_image(text: str) -> NDArray[np.uint8]:
    """base64 → ảnh BGR, qua đủ các bước kiểm của module."""
    return decode_image_bytes(decode_base64_strict(text))


def to_gray(image: NDArray) -> NDArray[np.uint8]:
    """Ảnh xám; ảnh đã xám thì giữ nguyên."""
    return cv2.cvtColor(image, cv2.COLOR_BGR2GRAY) if image.ndim == 3 else image


def resize_to_width(image: NDArray, width: int, allow_upscale: bool = False) -> NDArray:
    """Đưa ảnh về chiều rộng ``width`` giữ tỉ lệ; mặc định chỉ thu nhỏ."""
    h, w = image.shape[:2]
    if w == width or (w < width and not allow_upscale):
        return image
    scale = width / w
    interpolation = cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC
    return cv2.resize(
        image, (width, max(1, round(h * scale))), interpolation=interpolation
    )


def sharpness(image: NDArray) -> float:
    """Độ nét = phương sai của Laplacian; ảnh mờ có ít cạnh nên giá trị thấp."""
    return float(cv2.Laplacian(to_gray(image), cv2.CV_64F).var())


def brightness(image: NDArray) -> float:
    """Độ sáng trung bình (0..1) trên ảnh xám."""
    return float(to_gray(image).mean()) / 255.0


def encode_jpeg(image: NDArray, quality: int = 80, width: int | None = None) -> bytes:
    """Mã hoá JPEG (thu về ``width`` nếu có) — dùng cho khung bằng chứng và crop gửi AI."""
    if width is not None:
        image = resize_to_width(image, width)
    ok, buffer = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, int(quality)])
    if not ok:
        raise RuntimeError("Không mã hoá được JPEG.")
    return buffer.tobytes()


def decode_jpeg(data: bytes) -> NDArray[np.uint8]:
    """Giải mã JPEG nội bộ (khung đã lưu tạm), không kiểm lại như dữ liệu từ client."""
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        raise RuntimeError("Không giải mã được JPEG nội bộ.")
    return image
