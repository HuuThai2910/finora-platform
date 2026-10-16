"""Schema hợp đồng ``/document`` và ``/liveness`` (EKYC-002 mục 2)."""

import pytest
from pydantic import ValidationError

from app.schemas.ekyc import DocumentRequest, DocumentResponse, LivenessSpec

STEPS = [
    {"action": "FRONTAL", "hold_ms": 1500},
    {"action": "TURN_LEFT", "hold_ms": 1300},
    {"action": "FRONTAL", "hold_ms": 1000},
]


class TestDocumentRequest:
    def test_mac_dinh(self):
        request = DocumentRequest(front_image_base64="a", back_image_base64="b")
        assert request.front_source == "IN_APP_CAMERA"
        assert request.back_source == "IN_APP_CAMERA"
        assert request.retake_count == 0
        assert request.qr_text is None and request.qr_side is None

    @pytest.mark.parametrize(
        "changes",
        [
            {"qr_side": "LEFT"},
            {"front_source": "CAMERA"},
            {"retake_count": -1},
            {"front_image_base64": ""},
            {"back_image_base64": "x" * 6_000_001},
        ],
    )
    def test_sai_khuon(self, changes):
        data = {"front_image_base64": "a", "back_image_base64": "b", **changes}
        with pytest.raises(ValidationError):
            DocumentRequest(**data)


class TestLivenessSpec:
    def test_hop_le(self):
        spec = LivenessSpec(steps=STEPS, portrait_embedding_b64="x")
        assert spec.capture_mode == "VIDEO"
        assert spec.platform is None

    @pytest.mark.parametrize(
        ("raw", "expected"),
        [
            ("ios", "ios"),
            ("Android", "android"),
            (" WEB ", "web"),
            ("windows", None),
            (None, None),
        ],
    )
    def test_nen_tang_duoc_chuan_hoa(self, raw, expected):
        spec = LivenessSpec(steps=STEPS, portrait_embedding_b64="x", platform=raw)
        assert spec.platform == expected

    @pytest.mark.parametrize(
        "changes",
        [
            {
                "steps": [
                    {"action": "TURN_LEFT", "hold_ms": 1000},
                    {"action": "LOOK_UP", "hold_ms": 1000},
                ]
            },  # không có FRONTAL
            {
                "steps": [
                    {"action": "FRONTAL", "hold_ms": 1000},
                    {"action": "FRONTAL", "hold_ms": 1000},
                ]
            },  # không có hành động
            {
                "steps": [
                    {"action": "FRONTAL", "hold_ms": 50},
                    {"action": "TURN_LEFT", "hold_ms": 1000},
                ]
            },  # hold quá ngắn
            {
                "steps": [
                    {"action": "SMILE", "hold_ms": 1000},
                    {"action": "FRONTAL", "hold_ms": 1000},
                ]
            },
            {"capture_mode": "STILLS"},  # thiếu frame_times_ms
            {"capture_mode": "STILLS", "frame_times_ms": [0, 1000, 1000]},
            {"capture_mode": "BURST"},
        ],
    )
    def test_sai_khuon(self, changes):
        data = {"steps": STEPS, "portrait_embedding_b64": "x", **changes}
        with pytest.raises(ValidationError):
            LivenessSpec(**data)


def test_vi_du_response_trong_hop_dong_hop_le():
    """Ví dụ response ``/document`` trong hợp đồng phải parse được bằng schema."""
    example = {
        "decision": "AUTO_OK",
        "card_type": "CCCD_CHIP_2021",
        "reasons": ["QR_MRZ_AGREE"],
        "warnings": ["ID_PROVINCE_UNKNOWN"],
        "retake": [{"side": "FRONT", "reason": "BLUR"}],
        "fields": {
            "id_number": {"value": "001095012345", "sources": ["QR", "MRZ"]},
            "full_name": {"value": "NGUYỄN VĂN A", "sources": ["QR"]},
            "date_of_birth": {"value": "15/03/1995", "sources": ["QR", "MRZ"]},
            "gender": {"value": "Nam", "sources": ["QR", "MRZ"]},
            "address": {"value": "…", "sources": ["QR"]},
            "place_of_origin": {"value": None, "sources": []},
            "birth_registration_place": {"value": None, "sources": []},
            "issue_date": {"value": "01/07/2021", "sources": ["QR"]},
            "expiry_date": {
                "value": "15/03/2035",
                "sources": ["MRZ"],
                "no_expiry": False,
            },
        },
        "checks": {
            "qr": {"found": True, "by": "CLIENT", "side": "FRONT", "valid": True},
            "mrz": {
                "found": True,
                "valid": True,
                "engine": "TESSERACT",
                "doc_number_ok": True,
                "dob_ok": True,
                "expiry_ok": True,
                "composite_ok": True,
                "id12_ok": True,
            },
            "id_rules_ok": True,
            "sources_agree": True,
            "front_back_linked": True,
        },
        "quality": {
            "front": {
                "card_found": True,
                "sharpness": 152.3,
                "glare_ratio": 0.004,
                "brightness": 0.55,
                "ok": True,
            },
            "back": {
                "card_found": True,
                "sharpness": 140.1,
                "glare_ratio": 0.01,
                "brightness": 0.51,
                "ok": True,
            },
        },
        "portrait": {
            "found": True,
            "ied_px": 64.0,
            "sharpness": 63.1,
            "embedding_b64": "AAAA",
        },
        "ai_used": False,
        "thresholds_version": "doc-2026-10-placeholder",
    }
    parsed = DocumentResponse.model_validate(example)
    assert parsed.fields.expiry_date.no_expiry is False
    with pytest.raises(ValidationError):
        DocumentResponse.model_validate({**example, "decision": "VERIFIED"})
