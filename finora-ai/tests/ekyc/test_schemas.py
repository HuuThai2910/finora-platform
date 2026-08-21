"""Kiểm tra schema OCR eKYC."""

import pytest
from pydantic import ValidationError

from app.schemas.ekyc import OcrRequest, OcrResponse


def test_ocr_request_valid():
    request = OcrRequest(image_base64="abc123")
    assert request.image_base64 == "abc123"


def test_ocr_request_empty_rejected():
    with pytest.raises(ValidationError):
        OcrRequest(image_base64="")


def test_ocr_response_success():
    response = OcrResponse(
        success=True,
        id_number="079204001234",
        full_name="NGUYỄN VĂN A",
        date_of_birth="01/01/2000",
        gender="Nam",
        place_of_origin="TP Hồ Chí Minh",
        address="1 Lê Lợi, Quận 1",
        confidence=0.92,
    )
    assert response.success is True
    assert response.address == "1 Lê Lợi, Quận 1"


def test_ocr_response_failure():
    response = OcrResponse(success=False, confidence=0.0)
    assert response.id_number is None
    assert response.address is None
