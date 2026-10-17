"""Test dependency xác thực trên một app FastAPI nhỏ, không phụ thuộc router thật."""

import jwt
import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.api.deps import require_admin, require_internal_or_admin, require_principal
from app.api.errors import ApiError, register_error_handlers
from app.services.auth.keycloak_jwt import Principal
from .jwt_factory import (
    INTERNAL_TOKEN_TEST,
    FakeJwksClient,
    bearer,
    internal_headers,
    make_verifier,
    sign_token,
    use_test_verifier,
)


def _app() -> FastAPI:
    app = FastAPI()
    register_error_handlers(app)

    @app.get("/me")
    async def me(principal: Principal = Depends(require_principal)) -> dict:
        return {"userId": principal.user_id, "roles": sorted(principal.roles)}

    @app.put("/admin-only")
    async def admin_only(principal: Principal = Depends(require_admin)) -> dict:
        return {"userId": principal.user_id}

    @app.post("/internal-or-admin", dependencies=[Depends(require_internal_or_admin)])
    async def internal_or_admin() -> dict:
        return {"ok": True}

    @app.get("/loi")
    async def loi() -> dict:
        raise ApiError(409, "XUNG_DOT", "Dữ liệu đã đổi")

    return app


@pytest.fixture
def client(monkeypatch) -> TestClient:
    use_test_verifier(monkeypatch)
    return TestClient(_app())


class TestApiError:
    def test_tra_json_code_message(self, client):
        r = client.get("/loi")
        assert r.status_code == 409
        assert r.json() == {"code": "XUNG_DOT", "message": "Dữ liệu đã đổi"}

    def test_app_that_da_dang_ky_handler(self):
        from main import app

        assert ApiError in app.exception_handlers


class TestRequirePrincipal:
    def test_bearer_hop_le(self, client):
        r = client.get("/me", headers=bearer(sign_token("ROLE_INVESTOR", user_id="27")))
        assert r.status_code == 200
        assert r.json() == {"userId": "27", "roles": ["ROLE_INVESTOR"]}

    def test_cookie_access_token(self, client):
        client.cookies.set("access_token", sign_token("ROLE_ADMIN", user_id="1"))
        assert client.get("/me").json()["userId"] == "1"

    def test_bearer_thang_cookie(self, client):
        client.cookies.set("access_token", sign_token(user_id="tu-cookie"))
        r = client.get("/me", headers=bearer(sign_token(user_id="tu-header")))
        assert r.json()["userId"] == "tu-header"

    def test_thieu_token(self, client):
        r = client.get("/me")
        assert r.status_code == 401
        assert r.json()["code"] == "AUTH_REQUIRED"
        assert r.headers["WWW-Authenticate"] == "Bearer"

    def test_token_het_han(self, client):
        r = client.get("/me", headers=bearer(sign_token(exp_in=-300)))
        assert r.status_code == 401
        assert r.json()["code"] == "AUTH_EXPIRED"

    def test_token_sai(self, client):
        r = client.get("/me", headers=bearer("abc.def.ghi"))
        assert r.status_code == 401
        assert r.json()["code"] == "AUTH_INVALID"

    def test_keycloak_khong_tra_khoa_thi_503(self, monkeypatch):
        use_test_verifier(
            monkeypatch,
            make_verifier(jwks_client=FakeJwksClient(loi=jwt.PyJWKClientConnectionError("down"))),
        )
        r = TestClient(_app()).get("/me", headers=bearer(sign_token()))
        assert r.status_code == 503
        assert r.json()["code"] == "AUTH_UNAVAILABLE"


class TestRequireAdmin:
    def test_admin_qua(self, client):
        r = client.put("/admin-only", headers=bearer(sign_token("ROLE_ADMIN", user_id="1")))
        assert r.status_code == 200

    def test_khong_phai_admin_403(self, client):
        r = client.put("/admin-only", headers=bearer(sign_token("ROLE_BORROWER", "ROLE_INVESTOR")))
        assert r.status_code == 403
        assert r.json() == {
            "code": "ADMIN_ROLE_REQUIRED",
            "message": "Tài khoản không có quyền quản trị để thực hiện thao tác này",
        }

    def test_chua_dang_nhap_401_khong_phai_403(self, client):
        assert client.put("/admin-only").status_code == 401


class TestRequireInternalOrAdmin:
    def test_ma_noi_bo_dung(self, client, monkeypatch):
        r = client.post("/internal-or-admin", headers=internal_headers(monkeypatch))
        assert r.status_code == 200

    def test_ma_noi_bo_sai(self, client, monkeypatch):
        internal_headers(monkeypatch)
        r = client.post("/internal-or-admin", headers={"X-Internal-Token": "doan-bua"})
        assert r.status_code == 401
        assert r.json()["code"] == "INTERNAL_TOKEN_INVALID"

    def test_ma_noi_bo_sai_khong_roi_xuong_jwt(self, client, monkeypatch):
        """Header nội bộ sai thì từ chối ngay, kể cả khi kèm JWT admin hợp lệ."""
        internal_headers(monkeypatch)
        r = client.post(
            "/internal-or-admin",
            headers={"X-Internal-Token": "doan-bua", **bearer(sign_token("ROLE_ADMIN"))},
        )
        assert r.status_code == 401

    def test_chua_cau_hinh_ma_noi_bo_thi_tu_choi(self, client, monkeypatch):
        monkeypatch.delenv("AI_INTERNAL_TOKEN", raising=False)
        r = client.post("/internal-or-admin", headers={"X-Internal-Token": INTERNAL_TOKEN_TEST})
        assert r.status_code == 401
        assert r.json()["code"] == "INTERNAL_TOKEN_INVALID"

    def test_ma_rong_cung_la_chua_cau_hinh(self, client, monkeypatch):
        monkeypatch.setenv("AI_INTERNAL_TOKEN", "  ")
        r = client.post("/internal-or-admin", headers={"X-Internal-Token": ""})
        assert r.status_code == 401

    def test_khong_header_nhung_la_admin(self, client, monkeypatch):
        internal_headers(monkeypatch)
        r = client.post("/internal-or-admin", headers=bearer(sign_token("ROLE_ADMIN")))
        assert r.status_code == 200

    def test_khong_header_va_khong_phai_admin(self, client):
        r = client.post("/internal-or-admin", headers=bearer(sign_token("ROLE_BORROWER")))
        assert r.status_code == 403

    def test_khong_gi_ca(self, client):
        r = client.post("/internal-or-admin")
        assert r.status_code == 401
        assert r.json()["code"] == "AUTH_REQUIRED"
