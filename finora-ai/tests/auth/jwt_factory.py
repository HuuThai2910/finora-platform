"""
Công cụ test xác thực: sinh khóa RSA, ký token giống Keycloak, JWKS giả, mã nội bộ.

Không gọi Keycloak thật: test phải chạy được trên máy vừa clone, không cần docker. Khóa
RSA sinh một lần cho cả phiên test vì sinh khóa 2048 bit mất khoảng 50 ms.
"""

import time
from dataclasses import dataclass
from functools import lru_cache
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from app.services.auth.keycloak_jwt import KeycloakJwtVerifier

ISSUER_LOCAL = "http://localhost:8180/realms/finora"
JWKS_URL_TEST = "http://keycloak.test/realms/finora/protocol/openid-connect/certs"
KID_TEST = "khoa-test"
INTERNAL_TOKEN_TEST = "ma-noi-bo-test-0123456789"


@lru_cache(maxsize=1)
def signing_key() -> rsa.RSAPrivateKey:
    """Khóa "của Keycloak" trong test: JWKS giả trả khóa công khai của khóa này."""
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@lru_cache(maxsize=1)
def foreign_key() -> rsa.RSAPrivateKey:
    """Khóa lạ, cùng `kid` nhưng không phải của Keycloak: để thử token giả chữ ký."""
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


def sign_token(
    *roles: str,
    sub: str = "kc-sub-1",
    user_id: str | None = "27",
    iss: str = ISSUER_LOCAL,
    exp_in: int = 600,
    key: rsa.RSAPrivateKey | None = None,
    kid: str = KID_TEST,
    extra: dict[str, Any] | None = None,
) -> str:
    """Ký access token RS256 có các claim Keycloak mà FINORA dùng.

    `roles` ghi đúng như Keycloak (`ROLE_ADMIN`...). `user_id=None` để thử token thiếu
    claim `user_id` (service account).
    """
    now = int(time.time())
    claims: dict[str, Any] = {
        "iss": iss,
        "sub": sub,
        "iat": now,
        "exp": now + exp_in,
        "azp": "finora-user-client",
        "realm_access": {"roles": list(roles)},
    }
    if user_id is not None:
        claims["user_id"] = user_id
    if extra:
        claims.update(extra)
    return jwt.encode(
        claims, key or signing_key(), algorithm="RS256", headers={"kid": kid}
    )


@dataclass(frozen=True)
class _KhoaKy:
    """Giả `jwt.PyJWK`: verifier chỉ đọc thuộc tính `key`."""

    key: Any


class FakeJwksClient:
    """Giả `jwt.PyJWKClient`: trả khóa công khai của `signing_key()` cho đúng `kid`.

    `loi` đặt sẵn một exception để thử nhánh JWKS không tải được.
    """

    def __init__(self, loi: Exception | None = None) -> None:
        self.loi = loi
        self.so_lan_goi = 0

    def get_signing_key_from_jwt(self, token: str) -> _KhoaKy:
        self.so_lan_goi += 1
        if self.loi is not None:
            raise self.loi
        kid = jwt.get_unverified_header(token).get("kid")
        if kid != KID_TEST:
            raise jwt.PyJWKClientError(f"Unable to find a signing key that matches: {kid}")
        return _KhoaKy(signing_key().public_key())


def make_verifier(
    issuers: tuple[str, ...] = (ISSUER_LOCAL,), jwks_client: FakeJwksClient | None = None
) -> KeycloakJwtVerifier:
    return KeycloakJwtVerifier(JWKS_URL_TEST, issuers, jwks_client=jwks_client or FakeJwksClient())


def use_test_verifier(monkeypatch: pytest.MonkeyPatch, verifier: KeycloakJwtVerifier | None = None) -> KeycloakJwtVerifier:
    """Cho `app.api.deps` dùng verifier test thay cho verifier đọc env (gọi Keycloak thật)."""
    from app.api import deps

    chon = verifier or make_verifier()
    monkeypatch.setattr(deps, "get_verifier", lambda: chon)
    return chon


def internal_headers(monkeypatch: pytest.MonkeyPatch) -> dict[str, str]:
    """Đặt `AI_INTERNAL_TOKEN` cho tiến trình test và trả header Loan sẽ gửi."""
    monkeypatch.setenv("AI_INTERNAL_TOKEN", INTERNAL_TOKEN_TEST)
    return {"X-Internal-Token": INTERNAL_TOKEN_TEST}


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
