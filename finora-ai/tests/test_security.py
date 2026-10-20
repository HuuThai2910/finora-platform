"""Xác thực JWT Keycloak và phân quyền endpoint AI (D35, W3A).

Khóa RSA sinh trong test thay cho Keycloak; JWKS được thay bằng khóa công khai của test nên không gọi mạng.
"""
import time

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

import main
from app import security

ISSUER = "http://localhost:8180/realms/finora"
_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def client(monkeypatch):
    # Bỏ override "đủ quyền" của conftest: request phải đi qua kiểm token thật.
    main.app.dependency_overrides.pop(security.current_caller, None)
    monkeypatch.setenv("KEYCLOAK_ISSUER_URI", ISSUER)
    monkeypatch.setattr(security, "_signing_key", lambda token: _KEY.public_key())
    return TestClient(main.app)


def _token(*, realm_roles=(), client_roles=None, azp="finora-loan-client", key=_KEY, issuer=ISSUER,
           expires_in=300):
    now = int(time.time())
    claims = {
        "iss": issuer,
        "sub": "service-account-" + azp,
        "azp": azp,
        "iat": now,
        "exp": now + expires_in,
        "realm_access": {"roles": list(realm_roles)},
        "resource_access": {azp: {"roles": list(client_roles or [])}},
    }
    return jwt.encode(claims, key, algorithm="RS256")


def _explain(client, token=None, cookie=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    cookies = {"access_token": cookie} if cookie else None
    # Body rỗng: qua được cổng quyền thì FastAPI trả 422 vì thiếu field, chưa chạy mô hình hay gọi CIC.
    return client.post("/api/v1/ai/credit/explain", json={}, headers=headers, cookies=cookies)


def test_scoring_without_token_is_rejected_before_any_cccd_lookup(client):
    response = _explain(client)

    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "AI_UNAUTHENTICATED"
    assert response.headers["WWW-Authenticate"] == "Bearer"


def test_borrower_token_cannot_score(client):
    response = _explain(client, _token(realm_roles=["ROLE_BORROWER"], azp="finora-user-client"))

    assert response.status_code == 403
    assert response.json()["detail"]["code"] == "AI_FORBIDDEN"


def test_loan_service_account_passes_the_scoring_gate(client):
    response = _explain(client, _token(client_roles=[security.CREDIT_SCORE]))

    assert response.status_code == 422


def test_admin_web_session_cookie_passes_the_scoring_gate(client):
    response = _explain(client, cookie=_token(realm_roles=["ROLE_ADMIN"], azp="finora-user-client"))

    assert response.status_code == 422


def test_token_signed_by_another_key_is_rejected(client):
    response = _explain(client, _token(client_roles=[security.CREDIT_SCORE], key=_OTHER_KEY))

    assert response.status_code == 401


def test_token_from_another_issuer_is_rejected(client):
    response = _explain(client, _token(client_roles=[security.CREDIT_SCORE], issuer="http://evil.test/realms/finora"))

    assert response.status_code == 401


def test_expired_token_is_rejected(client):
    response = _explain(client, _token(client_roles=[security.CREDIT_SCORE], expires_in=-60))

    assert response.status_code == 401
    assert "hết hạn" in response.json()["detail"]["message"]


def test_only_admin_reads_or_changes_scoring_config(client):
    loan = _token(client_roles=[security.CREDIT_SCORE])
    admin = _token(realm_roles=["ROLE_ADMIN"], azp="finora-user-client")

    assert client.get("/api/v1/ai/config/rules", headers={"Authorization": "Bearer " + loan}).status_code == 403
    assert client.put("/api/v1/ai/config/rules", json={"rules": []},
                      headers={"Authorization": "Bearer " + loan}).status_code == 403
    assert client.get("/api/v1/ai/config/rules", headers={"Authorization": "Bearer " + admin}).status_code == 200


def test_cccd_ocr_only_accepts_the_user_service_account(client):
    loan = _token(client_roles=[security.CREDIT_SCORE])
    user_service = _token(client_roles=[security.EKYC_OCR], azp="finora-user-client")

    assert client.post("/api/v1/ai/ekyc/ocr", json={}, headers={"Authorization": "Bearer " + loan}).status_code == 403
    assert client.post("/api/v1/ai/ekyc/ocr", json={},
                       headers={"Authorization": "Bearer " + user_service}).status_code == 422


def test_health_stays_public(client):
    assert client.get("/health").status_code == 200


def test_jwks_outage_is_a_temporary_error(client, monkeypatch):
    def unavailable(token):
        raise jwt.PyJWKClientConnectionError("down")

    monkeypatch.setattr(security, "_signing_key", unavailable)

    response = _explain(client, _token(client_roles=[security.CREDIT_SCORE]))

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "AI_AUTH_UNAVAILABLE"


def test_realm_and_client_roles_map_like_the_java_converter():
    claims = {"realm_access": {"roles": ["role_admin", "BORROWER"]},
              "resource_access": {"finora-loan-client": {"roles": ["ai:credit:score"]}}}

    assert security.authorities_of(claims) == frozenset({"ROLE_ADMIN", "ROLE_BORROWER", "ai:credit:score"})


@pytest.mark.parametrize("environment, enabled", [("local", True), ("e2e", True), ("demo", True),
                                                  ("staging", False), ("production", False)])
def test_api_docs_only_outside_staging_and_production(monkeypatch, environment, enabled):
    monkeypatch.setenv("FINORA_ENVIRONMENT", environment)

    assert security.api_docs_enabled() is enabled
