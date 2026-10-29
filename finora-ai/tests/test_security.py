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
           expires_in=300, ops_roles=None):
    now = int(time.time())
    resource_access = {azp: {"roles": list(client_roles or [])}}
    if ops_roles is not None:
        # Quyền nhân viên nằm ở client finora-ops, khác client azp (web đăng nhập bằng finora-user-client).
        resource_access["finora-ops"] = {"roles": list(ops_roles)}
    claims = {
        "iss": issuer,
        "sub": "service-account-" + azp,
        "azp": azp,
        "iat": now,
        "exp": now + expires_in,
        "realm_access": {"roles": list(realm_roles)},
        "resource_access": resource_access,
    }
    return jwt.encode(claims, key, algorithm="RS256")


def _staff(*ops_roles):
    """Token phiên web của nhân viên vận hành: realm role ROLE_STAFF + quyền ops:* của client finora-ops."""
    return _token(realm_roles=["ROLE_STAFF"], azp="finora-user-client", ops_roles=ops_roles)


def _bearer(token):
    return {"Authorization": "Bearer " + token}


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


_CONFIG_URLS = ("/api/v1/ai/config/product", "/api/v1/ai/config/rules")


def test_staff_with_ai_policy_view_reads_but_cannot_change_scoring_config(client):
    staff = _staff(security.OPS_AI_POLICY_VIEW)

    for url in _CONFIG_URLS:
        assert client.get(url, headers=_bearer(staff)).status_code == 200
        # Body rỗng: qua được cổng quyền thì 422; bị chặn ở cổng thì 403 trước khi kiểm body.
        response = client.put(url, json={}, headers=_bearer(staff))
        assert response.status_code == 403
        assert response.json()["detail"]["code"] == "AI_FORBIDDEN"


def test_staff_with_ai_policy_action_passes_the_config_write_gate(client):
    staff = _staff(security.OPS_AI_POLICY_VIEW, security.OPS_AI_POLICY_ACTION)

    for url in _CONFIG_URLS:
        assert client.put(url, json={}, headers=_bearer(staff)).status_code == 422


def test_ai_policy_action_alone_does_not_grant_reading_config(client):
    # Giống OpsFunction.canView() phía Java: Xem chỉ nhận ops:ai-policy:view hoặc ROLE_ADMIN.
    staff = _staff(security.OPS_AI_POLICY_ACTION)

    for url in _CONFIG_URLS:
        assert client.get(url, headers=_bearer(staff)).status_code == 403


def test_admin_reads_and_passes_the_config_write_gate(client):
    admin = _token(realm_roles=["ROLE_ADMIN"], azp="finora-user-client")

    for url in _CONFIG_URLS:
        assert client.get(url, headers=_bearer(admin)).status_code == 200
        assert client.put(url, json={}, headers=_bearer(admin)).status_code == 422


def test_staff_without_permissions_is_forbidden_everywhere(client):
    staff = _staff()

    for url in _CONFIG_URLS:
        assert client.get(url, headers=_bearer(staff)).status_code == 403
        assert client.put(url, json={}, headers=_bearer(staff)).status_code == 403
    assert _explain(client, staff).status_code == 403
    assert client.post("/api/v1/ai/fraud/detect", json={}, headers=_bearer(staff)).status_code == 403
    assert client.post("/api/v1/ai/ekyc/document", json={}, headers=_bearer(staff)).status_code == 403


def test_staff_with_ai_scoring_view_passes_the_explain_gate(client):
    assert _explain(client, _staff(security.OPS_AI_SCORING_VIEW)).status_code == 422
    # Quyền chấm thử AI không mở cấu hình chính sách AI.
    assert client.get("/api/v1/ai/config/rules",
                      headers=_bearer(_staff(security.OPS_AI_SCORING_VIEW))).status_code == 403


def test_ai_policy_permission_does_not_open_scoring(client):
    staff = _staff(security.OPS_AI_POLICY_VIEW, security.OPS_AI_POLICY_ACTION)

    assert _explain(client, staff).status_code == 403


def test_config_change_audit_log_names_the_staff_member(client, caplog, tmp_path, monkeypatch):
    from app.services.credit import product_config

    # Ghi vào file cấu hình tạm để test không đổi bộ luật thật.
    goc = product_config._CONFIG_PATH
    tam = tmp_path / "product_config.json"
    tam.write_text(goc.read_text(encoding="utf-8"), encoding="utf-8")
    monkeypatch.setattr(product_config, "_CONFIG_PATH", tam)
    product_config.reload()
    now = int(time.time())
    staff = jwt.encode({"iss": ISSUER, "sub": "nv-001", "preferred_username": "nhanvien.a", "azp": "finora-user-client",
                        "iat": now, "exp": now + 300, "realm_access": {"roles": ["ROLE_STAFF"]},
                        "resource_access": {"finora-ops": {"roles": [security.OPS_AI_POLICY_VIEW,
                                                                     security.OPS_AI_POLICY_ACTION]}}},
                       _KEY, algorithm="RS256")
    try:
        rules = client.get("/api/v1/ai/config/rules", headers=_bearer(staff)).json()["rules"]
        body = {"rules": [{k: v for k, v in luat.items() if v is not None} for luat in rules]}
        with caplog.at_level("WARNING", logger="finora.ai.audit"):
            response = client.put("/api/v1/ai/config/rules", json=body, headers=_bearer(staff))
    finally:
        monkeypatch.setattr(product_config, "_CONFIG_PATH", goc)
        product_config.reload()

    assert response.status_code == 200
    audit = [r.getMessage() for r in caplog.records if r.name == "finora.ai.audit"]
    assert any("actor=nv-001" in m and "username=nhanvien.a" in m for m in audit)


def test_cccd_ocr_only_accepts_the_user_service_account(client):
    # EKYC-002 thay /ekyc/ocr bằng /ekyc/document (đọc thẻ) và /ekyc/liveness; cả hai cùng quyền ai:ekyc:ocr.
    loan = _token(client_roles=[security.CREDIT_SCORE])
    user_service = _token(client_roles=[security.EKYC_OCR], azp="finora-user-client")

    assert client.post("/api/v1/ai/ekyc/document", json={},
                       headers={"Authorization": "Bearer " + loan}).status_code == 403
    assert client.post("/api/v1/ai/ekyc/liveness",
                       headers={"Authorization": "Bearer " + loan}).status_code == 403
    assert client.post("/api/v1/ai/ekyc/document").status_code == 401
    assert client.post("/api/v1/ai/ekyc/document", json={},
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


def test_client_roles_of_every_client_are_kept_including_finora_ops():
    claims = {"realm_access": {"roles": ["STAFF"]},
              "resource_access": {"finora-user-client": {"roles": ["uma_protection"]},
                                  "finora-ops": {"roles": ["ops:ai-policy:view", "ops:ai-scoring:view"]},
                                  "account": {"roles": ["view-profile"]}}}

    assert security.authorities_of(claims) == frozenset({"ROLE_STAFF", "uma_protection", "ops:ai-policy:view",
                                                         "ops:ai-scoring:view", "view-profile"})


@pytest.mark.parametrize("environment, enabled", [("local", True), ("e2e", True), ("demo", True),
                                                  ("staging", False), ("production", False)])
def test_api_docs_only_outside_staging_and_production(monkeypatch, environment, enabled):
    monkeypatch.setenv("FINORA_ENVIRONMENT", environment)

    assert security.api_docs_enabled() is enabled
