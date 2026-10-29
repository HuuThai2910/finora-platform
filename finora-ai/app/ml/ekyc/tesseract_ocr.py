"""Gọi Tesseract qua ``pytesseract`` — thiếu chương trình thì trả ``None``, không nổ.

Tesseract là phụ thuộc hệ thống (``apt-get install tesseract-ocr`` trong
``Dockerfile.ai``). Máy dev Windows thường không có: khi đó MRZ chuyển sang Gemini
(nếu bật) hoặc coi như không đọc được, và hồ sơ đi duyệt tay — không bao giờ làm hỏng
cả request. Đặt ``TESSERACT_CMD`` nếu file chạy không nằm trong ``PATH``.
"""

from __future__ import annotations

import logging
import os
from functools import lru_cache

from numpy.typing import NDArray

try:
    import pytesseract
except ImportError:  # thư viện chưa cài — coi như không có Tesseract
    pytesseract = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)


@lru_cache(maxsize=1)
def available() -> bool:
    """Có gọi được Tesseract không (kiểm một lần, log một lần)."""
    if pytesseract is None:
        logger.warning("Chưa cài pytesseract — bỏ qua OCR MRZ/số in bằng Tesseract.")
        return False
    command = os.getenv("TESSERACT_CMD", "").strip()
    if command:
        pytesseract.pytesseract.tesseract_cmd = command
    try:
        version = pytesseract.get_tesseract_version()
    except Exception:  # noqa: BLE001 — mọi kiểu lỗi đều nghĩa là không chạy được
        logger.warning(
            "Không tìm thấy Tesseract — bỏ qua OCR MRZ/số in bằng Tesseract."
        )
        return False
    logger.info("Tesseract %s sẵn sàng cho eKYC.", version)
    return True


def image_to_text(image: NDArray, config: str, timeout_s: float) -> str | None:
    """Chạy Tesseract (``eng``) trên ảnh; lỗi/quá giờ thì ``None`` và ghi WARNING."""
    if not available():
        return None
    try:
        return pytesseract.image_to_string(
            image, lang="eng", config=config, timeout=timeout_s
        )
    except RuntimeError:
        # pytesseract ném RuntimeError khi vượt timeout.
        logger.warning("Tesseract quá %.1f giây, bỏ qua lượt đọc này.", timeout_s)
    except Exception as exc:  # noqa: BLE001 — một lượt OCR hỏng không được làm hỏng request
        logger.warning("Tesseract lỗi (%s), bỏ qua lượt đọc này.", type(exc).__name__)
    return None
