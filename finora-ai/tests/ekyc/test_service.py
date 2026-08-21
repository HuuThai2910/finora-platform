"""Kiểm tra EkycService — chọn engine OCR và fallback."""

import base64
from unittest.mock import MagicMock, patch

from app.services.ekyc.service import EkycService

OCR_OK = {
    "success": True,
    "id_number": "079204001234",
    "full_name": "NGUYỄN VĂN A",
    "date_of_birth": None,
    "gender": None,
    "place_of_origin": None,
    "address": None,
    "confidence": 0.9,
}


def _image_base64() -> str:
    return base64.b64encode(b"anh-gia-lap").decode()


def _service() -> EkycService:
    # Không dựng engine thật trong test: EasyOCR nặng, Gemini cần key.
    with patch("app.services.ekyc.service.OcrExtractor"), patch(
        "app.services.ekyc.service.gemini_extractor.build_from_env", return_value=None
    ):
        return EkycService()


def test_khong_co_gemini_thi_dung_easyocr():
    service = _service()
    service._ocr = MagicMock()
    service._ocr.extract.return_value = OCR_OK

    result = service.ocr(_image_base64())

    assert result["id_number"] == "079204001234"
    service._ocr.extract.assert_called_once()


def test_co_gemini_thi_uu_tien_gemini():
    service = _service()
    service._gemini_ocr = MagicMock()
    service._gemini_ocr.extract.return_value = OCR_OK
    service._ocr = MagicMock()

    result = service.ocr(_image_base64())

    assert result["success"] is True
    service._ocr.extract.assert_not_called()


def test_gemini_loi_thi_roi_ve_easyocr():
    service = _service()
    service._gemini_ocr = MagicMock()
    service._gemini_ocr.extract.side_effect = RuntimeError("mat mang")
    service._ocr = MagicMock()
    service._ocr.extract.return_value = OCR_OK

    result = service.ocr(_image_base64())

    assert result["id_number"] == "079204001234"
    service._ocr.extract.assert_called_once()
