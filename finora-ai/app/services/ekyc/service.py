"""Orchestrator cho eKYC — chỉ còn OCR giấy tờ (đã bỏ face match/liveness).

Luồng định danh hiện tại: người dùng chụp hai mặt CCCD, ``finora-user`` gọi
``/ocr`` trên ảnh mặt trước; quyết định nghiệp vụ ``VERIFIED`` thuộc về
``finora-user`` theo F01. Engine chính là Gemini vision (khi có key), EasyOCR
là dự phòng offline.
"""

import base64
import logging
import os
import time
from pathlib import Path

from app.ml.ekyc import gemini_extractor
from app.ml.ekyc.ocr_extractor import OcrExtractor

logger = logging.getLogger(__name__)


def _dump_debug_image(image_bytes: bytes) -> None:
    """Lưu ảnh OCR nhận được ra file khi đặt biến ``EKYC_DEBUG_DIR``.

    Chỉ dành cho debug local (xem tận mắt ảnh server nhận: đúng chiều chưa, nét
    không). Ảnh giấy tờ là PII — KHÔNG bật ở môi trường thật, không commit ảnh.
    """
    debug_dir = os.getenv("EKYC_DEBUG_DIR")
    if not debug_dir:
        return
    try:
        target = Path(debug_dir)
        target.mkdir(parents=True, exist_ok=True)
        path = target / f"ocr-{int(time.time() * 1000)}.jpg"
        path.write_bytes(image_bytes)
        logger.info("Đã lưu ảnh OCR debug: %s", path)
    except OSError:
        logger.exception("Không lưu được ảnh OCR debug.")


class EkycService:
    """Dịch vụ eKYC.

    Chỉ trả bằng chứng kỹ thuật (kết quả OCR). Quyết định nghiệp vụ
    ``VERIFIED``/``MANUAL_REVIEW`` thuộc về ``finora-user`` theo F01.
    """

    def __init__(self):
        self._ocr = OcrExtractor()
        # Có GEMINI_API_KEY thì đọc CCCD bằng Gemini vision (bền với ảnh mờ/loá
        # hơn hẳn); EasyOCR giữ vai trò dự phòng khi thiếu key hoặc gọi lỗi.
        self._gemini_ocr = gemini_extractor.build_from_env()

    def ocr(self, image_base64: str) -> dict:
        """OCR trích xuất thông tin từ ảnh CCCD."""
        image_bytes = base64.b64decode(image_base64)
        _dump_debug_image(image_bytes)

        if self._gemini_ocr is not None:
            try:
                return self._gemini_ocr.extract(image_bytes)
            except Exception:
                # Lỗi mạng/quota/key — không phải lỗi ảnh, nên thử tiếp EasyOCR
                # thay vì trả thất bại ngay.
                logger.exception("Gemini OCR lỗi — rơi về EasyOCR.")

        return self._ocr.extract(image_bytes)
