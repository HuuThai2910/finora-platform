"""Test API /api/v1/ai/assistant: bật/tắt, xác thực, vai trò, hạn mức, /suggestions, /feedback.

Verifier JWT, settings, bộ đếm hạn mức và orchestrator đều được thay bằng bản giả qua
`app.dependency_overrides`, nên test không cần Keycloak hay LLM.
"""

import json
import logging

import pytest
from fastapi.testclient import TestClient

import main
from app.api.assistant_router import get_user_rate_limiter
from app.api.errors import ApiError
from app.schemas.assistant import ChatResponse, SuggestionItem
from app.services.assistant.llm.quota import UserRateLimiter
from app.services.assistant.orchestrator import get_orchestrator
from app.services.assistant.principal import Toolset
from app.services.assistant.settings import get_settings
from app.services.auth.keycloak_jwt import AuthError, get_verifier
from main import app

from .helpers import make_principal, make_settings

URL = "/api/v1/ai/assistant"
CAU_HOI = {"message": "Kỳ tới tôi trả bao nhiêu?", "surface": "mobile"}

TOKEN = {
    "nguoi-vay": make_principal("ROLE_BORROWER", user_id="27"),
    "nha-dau-tu": make_principal("ROLE_INVESTOR"),
    "admin": make_principal("ROLE_ADMIN"),
    "sap-het-han": make_principal("ROLE_BORROWER", exp_in=30),
}


class VerifierGia:
    def __init__(self, khong_tai_duoc_khoa: bool = False) -> None:
        self.khong_tai_duoc_khoa = khong_tai_duoc_khoa

    def verify(self, token: str):
        if self.khong_tai_duoc_khoa:
            raise AuthError("AUTH_UNAVAILABLE", "JWKS lỗi")
        if token not in TOKEN:
            raise AuthError("AUTH_INVALID", "Chữ ký sai")
        return TOKEN[token]


class OrchestratorGia:
    def __init__(self) -> None:
        self.da_goi: list[tuple] = []
        self.loi: Exception | None = None

    async def handle(self, principal, toolsets, request):
        self.da_goi.append((principal, toolsets, request))
        if self.loi is not None:
            raise self.loi
        return ChatResponse(turn_id="0f9c3c1e-1111-2222-3333-444455556666", answer="Kỳ tới bạn trả 2.350.000 đ.",
                            mode="template")

    def suggestions(self, toolsets, screen):
        return [SuggestionItem(id=f"{min(t.value for t in toolsets).lower()}.goi_y", label=screen or "chung")]


@pytest.fixture
def moi_truong():
    """Thay mọi phụ thuộc bằng bản giả; mỗi test tự chỉnh `cai_dat`, `verifier`, `han_muc` nếu cần."""
    trang_thai = {
        "cai_dat": make_settings(),
        "verifier": VerifierGia(),
        "han_muc": UserRateLimiter(per_minute=10, per_day=100),
        "orchestrator": OrchestratorGia(),
    }
    app.dependency_overrides[get_settings] = lambda: trang_thai["cai_dat"]
    app.dependency_overrides[get_verifier] = lambda: trang_thai["verifier"]
    app.dependency_overrides[get_user_rate_limiter] = lambda: trang_thai["han_muc"]
    app.dependency_overrides[get_orchestrator] = lambda: trang_thai["orchestrator"]
    yield trang_thai, TestClient(app)
    app.dependency_overrides.clear()


def _bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


class TestXacThuc:
    def test_thieu_token(self, moi_truong):
        _, client = moi_truong
        res = client.post(f"{URL}/chat", json=CAU_HOI)
        assert res.status_code == 401
        assert res.json()["code"] == "ASSISTANT_UNAUTHENTICATED"

    def test_token_sai(self, moi_truong):
        _, client = moi_truong
        res = client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("gia-mao"))
        assert res.status_code == 401
        assert res.json()["code"] == "ASSISTANT_UNAUTHENTICATED"

    def test_token_con_duoi_60_giay(self, moi_truong):
        trang_thai, client = moi_truong
        res = client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("sap-het-han"))
        assert res.status_code == 401
        assert res.json()["code"] == "ASSISTANT_UNAUTHENTICATED"
        assert trang_thai["orchestrator"].da_goi == []

    def test_cookie_access_token_cua_web(self, moi_truong):
        _, client = moi_truong
        res = client.get(f"{URL}/suggestions", params={"surface": "web"}, headers={"Cookie": "access_token=admin"})
        assert res.status_code == 200

    def test_khong_tai_duoc_khoa_keycloak_la_503(self, moi_truong):
        trang_thai, client = moi_truong
        trang_thai["verifier"] = VerifierGia(khong_tai_duoc_khoa=True)
        res = client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("nguoi-vay"))
        assert res.status_code == 503
        assert res.json()["code"] == "ASSISTANT_AUTH_UNAVAILABLE"


class TestCongTacVaVaiTro:
    def test_tro_ly_bi_tat_tra_503_ke_ca_khi_chua_dang_nhap(self, moi_truong):
        trang_thai, client = moi_truong
        trang_thai["cai_dat"] = make_settings(enabled=False)
        assert client.post(f"{URL}/chat", json=CAU_HOI).json()["code"] == "ASSISTANT_DISABLED"
        res = client.get(f"{URL}/suggestions", params={"surface": "mobile"}, headers=_bearer("nguoi-vay"))
        assert res.status_code == 503
        assert res.json() == {"code": "ASSISTANT_DISABLED", "message": "Trợ lý FINORA đang tạm tắt."}

    def test_nguoi_vay_tren_web_bi_403(self, moi_truong):
        _, client = moi_truong
        res = client.post(f"{URL}/chat", json={**CAU_HOI, "surface": "web"}, headers=_bearer("nguoi-vay"))
        assert res.status_code == 403
        assert res.json()["code"] == "ASSISTANT_ROLE_NOT_SUPPORTED"

    def test_vai_tro_bi_tat_bang_cau_hinh_thi_403(self, moi_truong):
        trang_thai, client = moi_truong
        trang_thai["cai_dat"] = make_settings(toolsets=frozenset({Toolset.BORROWER}))
        res = client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("nha-dau-tu"))
        assert res.status_code == 403


class TestChat:
    def test_tra_loi_dang_camel_case(self, moi_truong):
        trang_thai, client = moi_truong
        body = {**CAU_HOI, "context": {"screen": "LoanServicingDetail", "params": {"loanNumber": "LN-1"}},
                "suggestionId": "borrower.loan.next_due"}
        res = client.post(f"{URL}/chat", json=body, headers=_bearer("nguoi-vay"))

        assert res.status_code == 200
        du_lieu = res.json()
        assert du_lieu["turnId"] == "0f9c3c1e-1111-2222-3333-444455556666"
        assert du_lieu["answer"] == "Kỳ tới bạn trả 2.350.000 đ."
        assert du_lieu["mode"] == "template"
        assert du_lieu["cards"] == [] and du_lieu["disclaimer"] is None
        principal, toolsets, request = trang_thai["orchestrator"].da_goi[0]
        assert principal.user_id == "27"
        assert toolsets == frozenset({Toolset.BORROWER})
        assert request.suggestion_id == "borrower.loan.next_due"

    def test_vuot_han_muc_tra_429(self, moi_truong):
        trang_thai, client = moi_truong
        trang_thai["han_muc"] = UserRateLimiter(per_minute=1, per_day=100)
        assert client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("nguoi-vay")).status_code == 200
        res = client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("nguoi-vay"))
        assert res.status_code == 429
        assert res.json()["code"] == "ASSISTANT_RATE_LIMITED"
        assert len(trang_thai["orchestrator"].da_goi) == 1

    def test_body_sai_khuon_tra_422(self, moi_truong):
        _, client = moi_truong
        res = client.post(f"{URL}/chat", json={"message": "   ", "surface": "mobile"}, headers=_bearer("nguoi-vay"))
        assert res.status_code == 422

    def test_phien_het_han_tu_orchestrator_thanh_401(self, moi_truong):
        trang_thai, client = moi_truong
        trang_thai["orchestrator"].loi = ApiError(401, "ASSISTANT_UNAUTHENTICATED", "Phiên đăng nhập đã hết hạn.")
        res = client.post(f"{URL}/chat", json=CAU_HOI, headers=_bearer("nguoi-vay"))
        assert res.status_code == 401
        assert res.json()["code"] == "ASSISTANT_UNAUTHENTICATED"


class TestSuggestionsVaFeedback:
    def test_goi_y_theo_vai_tro_va_man(self, moi_truong):
        _, client = moi_truong
        res = client.get(f"{URL}/suggestions", params={"surface": "mobile", "screen": "Portfolio"},
                         headers=_bearer("nha-dau-tu"))
        assert res.status_code == 200
        assert res.json() == [{"id": "investor.goi_y", "label": "Portfolio"}]

    def test_surface_la_tra_422(self, moi_truong):
        _, client = moi_truong
        res = client.get(f"{URL}/suggestions", params={"surface": "tv"}, headers=_bearer("nguoi-vay"))
        assert res.status_code == 422

    def test_admin_tren_mobile_bi_403(self, moi_truong):
        _, client = moi_truong
        res = client.get(f"{URL}/suggestions", params={"surface": "mobile"}, headers=_bearer("admin"))
        assert res.status_code == 403

    def test_feedback_204_va_chi_ghi_sieu_du_lieu(self, moi_truong, caplog):
        caplog.set_level(logging.INFO, logger="finora.assistant.audit")
        _, client = moi_truong
        res = client.post(f"{URL}/feedback", json={"turnId": "0f9c3c1e-1111-2222-3333-444455556666", "rating": "down"},
                          headers=_bearer("nguoi-vay"))
        assert res.status_code == 204
        assert res.content == b""
        dong = [r.getMessage() for r in caplog.records if r.getMessage().startswith("assistant_feedback ")]
        ban_ghi = json.loads(dong[-1].split(" ", 1)[1])
        assert ban_ghi["rating"] == "down"
        assert ban_ghi["user"] != "27"

    def test_feedback_rating_la_tra_422(self, moi_truong):
        _, client = moi_truong
        res = client.post(f"{URL}/feedback", json={"turnId": "x", "rating": "meh"}, headers=_bearer("nguoi-vay"))
        assert res.status_code == 422


class TestKhoiDong:
    def test_bat_tro_ly_thi_dung_san_orchestrator(self, monkeypatch):
        da_dung = []
        monkeypatch.setattr(main, "get_settings", lambda: make_settings(enabled=True))
        monkeypatch.setattr(main, "get_orchestrator", lambda: da_dung.append(1))
        with TestClient(app):
            pass
        assert da_dung == [1]

    def test_tat_tro_ly_thi_khong_dung(self, monkeypatch):
        da_dung = []
        monkeypatch.setattr(main, "get_settings", lambda: make_settings(enabled=False))
        monkeypatch.setattr(main, "get_orchestrator", lambda: da_dung.append(1))
        with TestClient(app):
            pass
        assert da_dung == []

    def test_kho_kien_thuc_hong_khong_lam_sap_service(self, monkeypatch, caplog):
        def hong():
            raise ValueError("chung.yaml: trùng id")

        monkeypatch.setattr(main, "get_settings", lambda: make_settings(enabled=True))
        monkeypatch.setattr(main, "get_orchestrator", hong)
        with TestClient(app) as client:
            assert client.get("/health").status_code == 200
        assert "Không dựng được Trợ lý FINORA" in caplog.text
