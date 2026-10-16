"""Pipeline ``/document`` trên cặp thẻ tổng hợp: QR thật (zxing), Gemini/Tesseract giả lập.

Thẻ tổng hợp không có khuôn mặt thật nên bước chân dung được thay bằng kết quả giả;
phần còn lại (nắn thẻ, chất lượng, giải QR, MRZ parse, hợp nhất) chạy thật.
"""

from datetime import date

import cv2
import numpy as np
import pytest

from app.ml.ekyc import face, mrz
from app.ml.ekyc.face import PortraitResult
from app.ml.ekyc.gemini_extractor import FrontRead
from app.schemas.ekyc import DocumentRequest
from app.services.ekyc import service as service_module
from app.services.ekyc.service import EkycService

from .synthetic import (
    MRZ_SYNTHETIC,
    SYNTHETIC_QR,
    b64,
    back_card,
    front_card,
    jpeg_bytes,
    on_background,
)

EMBEDDING = face.normalize(np.arange(1, 129, dtype=np.float32))


class FakeGemini:
    """Reader giả: ghi lại ảnh được gửi để kiểm quyền riêng tư."""

    def __init__(self, mrz_lines=None, front=None, front_id=None):
        self.mrz_lines = mrz_lines
        self.front = front
        self.front_id = front_id
        self.calls: list[tuple[str, bytes]] = []

    def read_mrz_lines(self, jpeg: bytes):
        self.calls.append(("mrz", jpeg))
        return list(self.mrz_lines) if self.mrz_lines else None

    def read_front_fields(self, jpeg: bytes, want_optional: bool):
        self.calls.append(("front_fields", jpeg))
        return self.front

    def read_front_id(self, jpeg: bytes):
        self.calls.append(("front_id", jpeg))
        return self.front_id


@pytest.fixture
def portrait_ok(monkeypatch):
    """Thay bước dò chân dung (thẻ giả không có mặt người)."""
    monkeypatch.setattr(service_module.face, "get_engine", lambda: object())
    monkeypatch.setattr(
        service_module.face,
        "extract_portrait",
        lambda engine, card, th: PortraitResult(
            found=True,
            reason=None,
            faces=1,
            ied_px=72.0,
            sharpness=150.0,
            embedding=EMBEDDING,
        ),
    )
    monkeypatch.setattr(service_module.face, "any_face", lambda *a, **k: False)
    monkeypatch.setattr(
        service_module.face, "mask_faces", lambda engine, image, *a: image
    )


@pytest.fixture
def no_tesseract(monkeypatch):
    monkeypatch.setattr(
        service_module.mrz, "read_with_tesseract", lambda card, timeout_s: None
    )
    monkeypatch.setattr(
        service_module.front_id_ocr, "front_shows_id", lambda *a, **k: None
    )


def request_for(front: np.ndarray, back: np.ndarray, **extra) -> tuple:
    front_bytes, back_bytes = jpeg_bytes(front), jpeg_bytes(back)
    request = DocumentRequest(
        front_image_base64=b64(front_bytes), back_image_base64=b64(back_bytes), **extra
    )
    return request, front_bytes, back_bytes


def test_the_2021_qr_server_va_mrz_gemini_thi_auto_ok(portrait_ok, no_tesseract):
    gemini = FakeGemini(mrz_lines=MRZ_SYNTHETIC)
    request, front, back = request_for(
        on_background(front_card(SYNTHETIC_QR)),
        on_background(back_card(MRZ_SYNTHETIC), angle=-8),
    )
    result = EkycService(gemini=gemini).analyze_document(request, front, back)

    assert result["decision"] == "AUTO_OK", result["reasons"]
    assert result["card_type"] == "CCCD_CHIP_2021"
    assert result["checks"]["qr"] == {
        "found": True,
        "by": "SERVER",
        "side": "FRONT",
        "valid": True,
    }
    assert result["checks"]["mrz"]["engine"] == "GEMINI"
    assert result["checks"]["front_back_linked"]
    assert result["fields"]["id_number"] == {
        "value": "001095012345",
        "sources": ["QR", "MRZ"],
    }
    assert result["fields"]["full_name"]["value"] == "NGUYỄN VĂN A"
    assert result["quality"]["front"]["card_found"] is True
    assert result["portrait"]["found"] is True
    assert face.decode_embedding(result["portrait"]["embedding_b64"]).shape == (128,)
    assert result["ai_used"] is True
    assert result["thresholds_version"].startswith("doc-2026-10")
    # QR đã có tên + địa chỉ: Gemini chỉ được hỏi đúng phần thiếu (MRZ).
    assert [kind for kind, _ in gemini.calls] == ["mrz"]


def test_tesseract_doc_duoc_mrz_thi_khong_goi_ai(portrait_ok, monkeypatch):
    monkeypatch.setattr(
        service_module.mrz,
        "read_with_tesseract",
        lambda card, timeout_s: mrz.best_of([MRZ_SYNTHETIC], "TESSERACT"),
    )
    gemini = FakeGemini()
    request, front, back = request_for(
        front_card(SYNTHETIC_QR), back_card(MRZ_SYNTHETIC)
    )
    result = EkycService(gemini=gemini).analyze_document(request, front, back)
    assert result["decision"] == "AUTO_OK"
    assert result["ai_used"] is False
    assert gemini.calls == []


def test_khong_ai_khong_tesseract_thi_duyet_tay(portrait_ok, no_tesseract):
    request, front, back = request_for(
        front_card(SYNTHETIC_QR), back_card(MRZ_SYNTHETIC)
    )
    result = EkycService(gemini=None).analyze_document(request, front, back)
    assert result["decision"] == "NEEDS_REVIEW"
    assert {"MRZ_MISSING", "NOT_LINKED", "EXPIRY_UNREADABLE"} <= set(result["reasons"])
    assert result["checks"]["mrz"]["found"] is False
    assert result["ai_used"] is False


def test_the_2024_gan_hai_mat_bang_so_in_do_ai_doc(portrait_ok, no_tesseract):
    gemini = FakeGemini(mrz_lines=MRZ_SYNTHETIC, front_id="001095012345")
    request, front, back = request_for(
        front_card(None), back_card(MRZ_SYNTHETIC, SYNTHETIC_QR), qr_side="BACK"
    )
    result = EkycService(gemini=gemini).analyze_document(request, front, back)
    assert result["card_type"] == "CAN_CUOC_2024"
    assert result["checks"]["qr"]["side"] == "BACK"
    assert result["checks"]["front_back_linked"]
    assert result["decision"] == "AUTO_OK", result["reasons"]

    # Quyền riêng tư: ảnh mặt trước gửi AI phải bị che 38% bên trái (vùng chân dung).
    sent = dict(gemini.calls)["front_id"]
    image = cv2.imdecode(np.frombuffer(sent, np.uint8), cv2.IMREAD_COLOR)
    left = image[:, : int(image.shape[1] * 0.36)]
    assert left.mean() < 3.0
    assert image[:, int(image.shape[1] * 0.5) :].mean() > 100


def test_qr_client_khong_du_gan_hai_mat(portrait_ok, no_tesseract):
    # Mặt trước không có QR trên ảnh; chỉ có chuỗi client gửi → phải dựa vào số in.
    gemini = FakeGemini(mrz_lines=MRZ_SYNTHETIC, front_id=None)
    request, front, back = request_for(
        front_card(None),
        back_card(MRZ_SYNTHETIC),
        qr_text=SYNTHETIC_QR,
        qr_side="FRONT",
    )
    result = EkycService(gemini=gemini).analyze_document(request, front, back)
    assert result["checks"]["qr"]["by"] == "CLIENT"
    assert not result["checks"]["front_back_linked"]
    assert result["decision"] == "NEEDS_REVIEW"
    assert "front_id" in dict(gemini.calls)


def test_mrz_ai_sai_check_digit_thi_chup_lai_mat_sau(portrait_ok, no_tesseract):
    bad = (MRZ_SYNTHETIC[0], MRZ_SYNTHETIC[1][:-1] + "9", MRZ_SYNTHETIC[2])
    request, front, back = request_for(
        front_card(SYNTHETIC_QR), back_card(MRZ_SYNTHETIC)
    )
    result = EkycService(gemini=FakeGemini(mrz_lines=bad)).analyze_document(
        request, front, back
    )
    assert result["decision"] == "RETAKE"
    assert {"side": "BACK", "reason": "MRZ_UNREADABLE"} in result["retake"]


def test_ai_loi_khong_lam_hong_request(portrait_ok, no_tesseract):
    request, front, back = request_for(front_card(None), back_card(None))
    result = EkycService(gemini=FakeGemini()).analyze_document(request, front, back)
    # Không QR, không MRZ, AI không trả gì → không có số định danh → chụp lại.
    assert result["decision"] == "RETAKE"
    assert {"side": "FRONT", "reason": "NO_DATA"} in result["retake"]


def test_chi_ai_doc_duoc_the_ma_vach(portrait_ok, no_tesseract):
    front_read = FrontRead(
        card_title="CĂN CƯỚC CÔNG DÂN",
        id_number="001095012345",
        full_name="NGUYỄN VĂN A",
        date_of_birth=date(1995, 3, 15),
        gender="Nam",
        address="Hà Nội",
    )
    request, front, back = request_for(front_card(None), back_card(None))
    result = EkycService(gemini=FakeGemini(front=front_read)).analyze_document(
        request, front, back
    )
    assert result["decision"] == "NEEDS_REVIEW"
    assert result["card_type"] == "CCCD_BARCODE"
    assert result["fields"]["id_number"] == {"value": "001095012345", "sources": ["AI"]}


def test_chan_dung_khong_dung_duoc(monkeypatch, no_tesseract):
    monkeypatch.setattr(service_module.face, "get_engine", lambda: object())
    monkeypatch.setattr(
        service_module.face,
        "extract_portrait",
        lambda engine, card, th: PortraitResult(False, "NO_FACE", 0),
    )
    monkeypatch.setattr(service_module.face, "any_face", lambda *a, **k: False)
    request, front, back = request_for(
        front_card(SYNTHETIC_QR), back_card(MRZ_SYNTHETIC)
    )
    result = EkycService(gemini=None).analyze_document(request, front, back)
    assert result["decision"] == "RETAKE"
    assert result["retake"][0] == {"side": "FRONT", "reason": "PORTRAIT_UNUSABLE"}
    assert "embedding_b64" not in result["portrait"]


def test_chup_nham_mat(monkeypatch, no_tesseract):
    monkeypatch.setattr(service_module.face, "get_engine", lambda: object())
    monkeypatch.setattr(
        service_module.face,
        "extract_portrait",
        lambda engine, card, th: PortraitResult(False, "NO_FACE", 0),
    )
    monkeypatch.setattr(service_module.face, "any_face", lambda *a, **k: True)
    request, front, back = request_for(
        back_card(MRZ_SYNTHETIC), front_card(SYNTHETIC_QR)
    )
    result = EkycService(gemini=None).analyze_document(request, front, back)
    assert result["retake"][0] == {"side": "FRONT", "reason": "WRONG_SIDE"}


@pytest.mark.parametrize(
    ("platform", "inverted"), [("ios", True), ("android", False), (None, False)]
)
def test_liveness_chon_chieu_quay_theo_nen_tang(monkeypatch, platform, inverted):
    from app.ml.ekyc.thresholds import LivenessThresholds
    from app.schemas.ekyc import LivenessSpec

    captured = {}

    def fake_analyze_video(data, steps, portrait, engine, th):
        captured["th"] = th
        return {
            "decision": "RETAKE",
            "reasons": ["NO_FACE"],
            "face_match": {"zone": None},
            "faces": {"sampled_frames": 0},
        }

    monkeypatch.setattr(service_module.face, "get_engine", lambda: object())
    monkeypatch.setattr(
        service_module.liveness_video, "analyze_video", fake_analyze_video
    )
    spec = LivenessSpec(
        steps=[
            {"action": "FRONTAL", "hold_ms": 1500},
            {"action": "TURN_LEFT", "hold_ms": 1300},
        ],
        portrait_embedding_b64="x",
        platform=platform,
    )
    service = EkycService(gemini=None, live_th=LivenessThresholds(yaw_invert_ios=True))
    service.analyze_liveness(spec, EMBEDDING, b"video", None)
    assert captured["th"].yaw_invert is inverted
