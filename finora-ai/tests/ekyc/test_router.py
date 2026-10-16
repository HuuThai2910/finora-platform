"""Router eKYC nội bộ: token, 422, 503 (thiếu model / bận), multipart liveness.

Dùng ``httpx.AsyncClient`` + ``ASGITransport`` thay cho ``TestClient``: bộ ghim hiện tại
(starlette 0.35.1 + httpx 0.28.1) làm ``TestClient`` lỗi ``unexpected keyword 'app'``.
"""

import json
import logging

import httpx
import numpy as np
import pytest

from app.api import ekyc_router
from app.ml.ekyc import face
from main import app

from .synthetic import b64, front_card, jpeg_bytes, moving_blob_frame, write_video

pytestmark = pytest.mark.asyncio

DOCUMENT_URL = "/api/v1/ai/ekyc/document"
LIVENESS_URL = "/api/v1/ai/ekyc/liveness"
JPEG = jpeg_bytes(front_card(None))
EMBEDDING_B64 = face.encode_embedding(np.ones(128, np.float32))
STEPS = [
    {"action": "FRONTAL", "hold_ms": 1500},
    {"action": "TURN_LEFT", "hold_ms": 1300},
    {"action": "FRONTAL", "hold_ms": 1100},
    {"action": "LOOK_UP", "hold_ms": 1600},
    {"action": "FRONTAL", "hold_ms": 900},
    {"action": "TURN_RIGHT", "hold_ms": 1400},
    {"action": "FRONTAL", "hold_ms": 1000},
]


async def post(path: str, headers: dict | None = None, **kwargs) -> httpx.Response:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post(path, headers=headers or {}, **kwargs)


def document_body(front: str | None = None, back: str | None = None) -> dict:
    return {
        "front_image_base64": front or b64(JPEG),
        "back_image_base64": back or b64(JPEG),
    }


def spec(**changes) -> str:
    return json.dumps(
        {"steps": STEPS, "portrait_embedding_b64": EMBEDDING_B64, **changes}
    )


DOCUMENT_RESULT = {
    "decision": "RETAKE",
    "card_type": "UNKNOWN",
    "reasons": ["PORTRAIT_UNUSABLE"],
    "warnings": [],
    "retake": [{"side": "FRONT", "reason": "PORTRAIT_UNUSABLE"}],
    "fields": {
        name: {"value": None, "sources": []}
        for name in (
            "id_number",
            "full_name",
            "date_of_birth",
            "gender",
            "address",
            "place_of_origin",
            "birth_registration_place",
            "issue_date",
        )
    }
    | {"expiry_date": {"value": None, "sources": [], "no_expiry": False}},
    "checks": {
        "qr": {"found": False, "by": None, "side": None, "valid": False},
        "mrz": {
            "found": False,
            "valid": False,
            "engine": None,
            "doc_number_ok": False,
            "dob_ok": False,
            "expiry_ok": False,
            "composite_ok": False,
            "id12_ok": False,
        },
        "id_rules_ok": False,
        "sources_agree": True,
        "front_back_linked": False,
    },
    "quality": {
        side: {
            "card_found": False,
            "sharpness": 10.0,
            "glare_ratio": 0.0,
            "brightness": 0.5,
            "ok": False,
        }
        for side in ("front", "back")
    },
    "portrait": {"found": False, "ied_px": None, "sharpness": None},
    "ai_used": False,
    "thresholds_version": "doc-2026-10-placeholder",
}


class FakeService:
    def __init__(self, document: dict | None = None):
        self.document = document or DOCUMENT_RESULT
        self.calls = 0

    def analyze_document(self, request, front, back):
        self.calls += 1
        assert front == JPEG and back == JPEG  # router chuyển đúng bytes đã giải base64
        return self.document


@pytest.fixture
def models_ok(monkeypatch):
    monkeypatch.setattr(ekyc_router.face, "ensure_models", lambda: None)


@pytest.fixture
def fake_service(monkeypatch, models_ok):
    service = FakeService()
    monkeypatch.setattr(ekyc_router, "get_ekyc_service", lambda: service)
    return service


async def test_endpoint_ocr_cu_da_bi_xoa():
    response = await post("/api/v1/ai/ekyc/ocr", json={"image_base64": b64(JPEG)})
    assert response.status_code == 404


class TestInternalToken:
    async def test_thieu_token_khi_da_cau_hinh(self, monkeypatch, fake_service):
        monkeypatch.setenv("AI_INTERNAL_TOKEN", "bi-mat-noi-bo")
        response = await post(DOCUMENT_URL, json=document_body())
        assert response.status_code == 401
        assert response.json() == {"detail": "INTERNAL_TOKEN_INVALID"}
        assert fake_service.calls == 0

    async def test_sai_token(self, monkeypatch, fake_service):
        monkeypatch.setenv("AI_INTERNAL_TOKEN", "bi-mat-noi-bo")
        response = await post(
            DOCUMENT_URL, json=document_body(), headers={"X-Internal-Token": "doan-bua"}
        )
        assert response.status_code == 401

    async def test_dung_token(self, monkeypatch, fake_service):
        monkeypatch.setenv("AI_INTERNAL_TOKEN", "bi-mat-noi-bo")
        response = await post(
            DOCUMENT_URL,
            json=document_body(),
            headers={"X-Internal-Token": "bi-mat-noi-bo"},
        )
        assert response.status_code == 200
        assert fake_service.calls == 1

    async def test_liveness_cung_can_token(self, monkeypatch, models_ok):
        monkeypatch.setenv("AI_INTERNAL_TOKEN", "bi-mat-noi-bo")
        response = await post(
            LIVENESS_URL,
            data={"spec": spec()},
            files={"video": ("v.mp4", b"x", "video/mp4")},
        )
        assert response.status_code == 401

    async def test_che_do_dev_canh_bao_mot_lan(self, monkeypatch, fake_service, caplog):
        monkeypatch.delenv("AI_INTERNAL_TOKEN", raising=False)
        monkeypatch.setattr(
            ekyc_router, "_dev_mode_warned", ekyc_router.threading.Event()
        )
        with caplog.at_level(logging.WARNING, logger="app.api.ekyc_router"):
            first = await post(DOCUMENT_URL, json=document_body())
            second = await post(DOCUMENT_URL, json=document_body())
        assert first.status_code == second.status_code == 200
        assert caplog.text.count("AI_INTERNAL_TOKEN rỗng") == 1


class TestDocumentValidation:
    @pytest.mark.parametrize(
        ("front", "code"),
        [
            ("@@khong-phai-base64@@", "IMAGE_BASE64_INVALID"),
            (b64(b"GIF89a khong ho tro"), "IMAGE_TYPE_UNSUPPORTED"),
            (b64(b"\xff\xd8\xff\xe0" + b"\x00" * 20), "IMAGE_DECODE_FAILED"),
        ],
    )
    async def test_anh_sai_tra_422(self, fake_service, front, code):
        response = await post(DOCUMENT_URL, json=document_body(front=front))
        assert response.status_code == 422
        assert response.json() == {"detail": code}
        assert fake_service.calls == 0

    async def test_thieu_truong_tra_422(self, fake_service):
        response = await post(DOCUMENT_URL, json={"front_image_base64": b64(JPEG)})
        assert response.status_code == 422

    async def test_qua_6_trieu_ky_tu_tra_422(self, fake_service):
        response = await post(DOCUMENT_URL, json=document_body(front="A" * 6_000_004))
        assert response.status_code == 422


class TestDocumentResponse:
    async def test_tra_dung_khuon_va_bo_embedding_khi_khong_thay_chan_dung(
        self, fake_service
    ):
        response = await post(DOCUMENT_URL, json=document_body())
        assert response.status_code == 200
        body = response.json()
        assert body["decision"] == "RETAKE"
        assert "embedding_b64" not in body["portrait"]
        assert body["fields"]["place_of_origin"] == {"value": None, "sources": []}

    async def test_co_embedding_khi_thay_chan_dung(self, monkeypatch, models_ok):
        document = json.loads(json.dumps(DOCUMENT_RESULT))
        document["portrait"] = {
            "found": True,
            "ied_px": 70.0,
            "sharpness": 120.0,
            "embedding_b64": EMBEDDING_B64,
        }
        monkeypatch.setattr(
            ekyc_router, "get_ekyc_service", lambda: FakeService(document)
        )
        response = await post(DOCUMENT_URL, json=document_body())
        assert response.json()["portrait"]["embedding_b64"] == EMBEDDING_B64


class TestServiceUnavailable:
    async def test_thieu_model_mat(self, monkeypatch):
        def missing():
            raise face.FaceModelsMissing("models/face")

        monkeypatch.setattr(ekyc_router.face, "ensure_models", missing)
        response = await post(DOCUMENT_URL, json=document_body())
        assert response.status_code == 503
        assert response.json() == {"detail": "FACE_MODELS_MISSING"}

    async def test_thieu_model_mat_ca_liveness(self, monkeypatch):
        def missing():
            raise face.FaceModelsMissing("models/face")

        monkeypatch.setattr(ekyc_router.face, "ensure_models", missing)
        response = await post(
            LIVENESS_URL,
            data={"spec": spec()},
            files={"video": ("v.mp4", b"video", "video/mp4")},
        )
        assert response.status_code == 503
        assert response.json() == {"detail": "FACE_MODELS_MISSING"}

    async def test_ban_tra_503_retry_after(self, fake_service):
        assert ekyc_router._heavy_slot.acquire(blocking=False)
        try:
            document = await post(DOCUMENT_URL, json=document_body())
            liveness = await post(
                LIVENESS_URL,
                data={"spec": spec()},
                files={"video": ("v.mp4", b"video", "video/mp4")},
            )
        finally:
            ekyc_router._heavy_slot.release()
        for response in (document, liveness):
            assert response.status_code == 503
            assert response.json() == {"detail": "BUSY"}
            assert response.headers["Retry-After"] == "3"
        assert fake_service.calls == 0

    async def test_slot_duoc_tra_lai_sau_moi_request(self, fake_service):
        for _ in range(2):
            response = await post(DOCUMENT_URL, json=document_body())
            assert response.status_code == 200
        assert ekyc_router._heavy_slot.acquire(blocking=False)
        ekyc_router._heavy_slot.release()


class TestLivenessValidation:
    @pytest.mark.parametrize(
        ("data", "files", "code"),
        [
            ({"spec": "{khong phai json"}, None, "SPEC_INVALID"),
            ({"spec": json.dumps({"steps": STEPS})}, None, "SPEC_INVALID"),
            (
                {"spec": spec(portrait_embedding_b64="AAAA")},
                None,
                "PORTRAIT_EMBEDDING_INVALID",
            ),
            ({"spec": spec()}, None, "VIDEO_MISSING"),
            (
                {"spec": spec(capture_mode="STILLS", frame_times_ms=[0, 1000])},
                [("frames", ("a.jpg", JPEG, "image/jpeg"))],
                "FRAME_TIMES_MISMATCH",
            ),
            (
                {"spec": spec(capture_mode="STILLS", frame_times_ms=[0])},
                [("frames", ("a.png", b"\x89PNG\r\n\x1a\nxx", "image/png"))],
                "FRAME_NOT_JPEG",
            ),
            (
                {"spec": spec(capture_mode="STILLS", frame_times_ms=[0])},
                None,
                "FRAMES_INVALID",
            ),
        ],
    )
    async def test_dau_vao_sai_tra_422(self, models_ok, data, files, code):
        response = await post(LIVENESS_URL, data=data, files=files)
        assert response.status_code == 422, response.text
        assert response.json() == {"detail": code}

    async def test_video_qua_10mb(self, models_ok):
        big = b"\x00" * (10 * 1024 * 1024 + 1)
        response = await post(
            LIVENESS_URL,
            data={"spec": spec()},
            files={"video": ("v.mp4", big, "video/mp4")},
        )
        assert response.status_code == 422
        assert response.json() == {"detail": "VIDEO_TOO_LARGE"}


async def test_liveness_video_tong_hop_khong_co_mat_thi_retake(face_engine):
    video = write_video(moving_blob_frame, frames=135, fps=15.0)
    response = await post(
        LIVENESS_URL,
        data={"spec": spec(platform="android")},
        files={"video": ("liveness.mp4", video, "video/mp4")},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["decision"] == "RETAKE"
    assert "NO_FACE" in body["reasons"]
    assert body["video"]["codec"] == "mpeg4"
    assert body["thresholds_version"].startswith("liveness-2026-10")


async def test_video_hong_thi_retake_video_unreadable(face_engine):
    response = await post(
        LIVENESS_URL,
        data={"spec": spec()},
        files={"video": ("v.mp4", b"khong phai video", "video/mp4")},
    )
    assert response.status_code == 200
    assert response.json()["reasons"] == ["VIDEO_UNREADABLE"]


async def test_health_van_chay():
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/health")
    assert response.status_code == 200
