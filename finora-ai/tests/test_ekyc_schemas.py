import pytest
from pydantic import ValidationError
from app.schemas.ekyc import (
    OcrRequest, OcrResponse,
    FaceMatchRequest, FaceMatchResponse,
    LivenessRequest, LivenessResponse,
)


def test_ocr_request_valid():
    req = OcrRequest(image_base64="aGVsbG8=")
    assert req.image_base64 == "aGVsbG8="


def test_ocr_request_empty_rejected():
    with pytest.raises(ValidationError):
        OcrRequest(image_base64="")


def test_ocr_response_success():
    resp = OcrResponse(
        success=True,
        id_number="079204001234",
        full_name="NGUYEN VAN A",
        date_of_birth="01/01/2000",
        gender="Nam",
        place_of_origin="TP Ho Chi Minh",
        confidence=0.92,
    )
    assert resp.success is True
    assert resp.id_number == "079204001234"


def test_ocr_response_failure():
    resp = OcrResponse(success=False, confidence=0.1)
    assert resp.success is False
    assert resp.id_number is None


def test_face_match_request_valid():
    req = FaceMatchRequest(selfie_base64="abc=", cccd_image_base64="def=")
    assert req.selfie_base64 == "abc="


def test_face_match_response():
    resp = FaceMatchResponse(match=True, similarity=0.87, threshold=0.6)
    assert resp.match is True
    assert resp.similarity == 0.87


def test_liveness_request_valid():
    req = LivenessRequest(image_base64="xyz=")
    assert req.image_base64 == "xyz="


def test_liveness_response():
    resp = LivenessResponse(is_live=True, confidence=0.95, method="lbp_texture")
    assert resp.is_live is True
    assert resp.method == "lbp_texture"
