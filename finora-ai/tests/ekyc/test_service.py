"""Kiểm tra EkycService — OCR qua Gemini, thiếu key là lỗi hạ tầng."""

import base64
from unittest.mock import MagicMock, patch

import pytest

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


def test_ocr_di_qua_gemini():
    with patch(
        "app.services.ekyc.service.gemini_extractor.build_from_env"
    ) as mock_build:
        engine = MagicMock()
        engine.extract.return_value = OCR_OK
        mock_build.return_value = engine

        result = EkycService().ocr(_image_base64())

    assert result["id_number"] == "079204001234"
    engine.extract.assert_called_once()


def test_thieu_key_thi_no_loi_ha_tang():
    """Thiếu key phải nổ RuntimeError để finora-user trả AI_UNAVAILABLE,
    không được giả dạng OCR thất bại rồi bắt người dùng chụp lại vô ích."""
    with patch(
        "app.services.ekyc.service.gemini_extractor.build_from_env", return_value=None
    ):
        service = EkycService()

    with pytest.raises(RuntimeError):
        service.ocr(_image_base64())
