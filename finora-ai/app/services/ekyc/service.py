"""Orchestrator cho eKYC — OCR giấy tờ bằng Gemini vision.

Luồng định danh: người dùng chụp hai mặt CCCD, ``finora-user`` gọi ``/ocr``
trên ảnh mặt trước; quyết định nghiệp vụ ``VERIFIED`` thuộc về ``finora-user``
theo F01. Engine duy nhất là Gemini (bắt buộc ``GEMINI_API_KEY``).
"""

import base64
import logging

from app.ml.ekyc import gemini_extractor

logger = logging.getLogger(__name__)


class EkycService:
    """Dịch vụ eKYC.

    Chỉ trả bằng chứng kỹ thuật (kết quả OCR). Quyết định nghiệp vụ
    ``VERIFIED``/``MANUAL_REVIEW`` thuộc về ``finora-user`` theo F01.
    """

    def __init__(self):
        self._gemini_ocr = gemini_extractor.build_from_env()
        if self._gemini_ocr is None:
            # Không chặn service khởi động (credit/fraud vẫn chạy) nhưng phải
            # kêu to ngay để người vận hành biết eKYC sẽ lỗi.
            logger.error(
                "Thiếu GEMINI_API_KEY — endpoint OCR eKYC sẽ trả lỗi cho tới khi cấu hình key."
            )

    def ocr(self, image_base64: str) -> dict:
        """OCR trích xuất thông tin từ ảnh CCCD.

        Thiếu cấu hình là lỗi hạ tầng: nổ ra ngoài để ``finora-user`` trả
        AI_UNAVAILABLE, không được giả dạng "ảnh mờ" bắt người dùng chụp lại.
        """
        if self._gemini_ocr is None:
            raise RuntimeError("Chưa cấu hình GEMINI_API_KEY cho OCR eKYC.")

        image_bytes = base64.b64decode(image_base64)
        return self._gemini_ocr.extract(image_bytes)
