"""Xác thực và phân quyền mọi endpoint của AI bằng JWT Keycloak (D35, W3A).

Trước W3A, AI mở hoàn toàn: ai gọi được cổng 8000 cũng gửi ``borrower_id`` bất kỳ để AI tự tra CCCD rồi hỏi CIC, hoặc
thay bộ luật chấm điểm đang áp dụng cho mọi hồ sơ. Nay mỗi request phải mang access token Keycloak (header
``Authorization: Bearer`` hoặc cookie ``access_token`` của web admin), được kiểm chữ ký RS256 qua JWKS, issuer và hạn.

Quyền đọc từ token giống ``KeycloakJwtAuthenticationConverter`` phía Java: realm role thành ``ROLE_*``, client role giữ
nguyên tên (ví dụ ``ai:credit:score`` của service account ``finora-loan-client``, ``ops:ai-policy:view`` của nhân viên
qua client ``finora-ops``). Client role của mọi client trong ``resource_access`` đều được giữ, không lọc theo client.
"""
from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from functools import lru_cache
from typing import Callable

import jwt
from fastapi import Depends, HTTPException, Request, status

logger = logging.getLogger(__name__)

ROLE_ADMIN = "ROLE_ADMIN"
CREDIT_SCORE = "ai:credit:score"
EKYC_OCR = "ai:ekyc:ocr"
FRAUD_DETECT = "ai:fraud:detect"

# Quyền nhân viên vận hành: client role của client ``finora-ops`` (resource_access["finora-ops"].roles), đặt tên
# ``ops:<chức năng>:view`` / ``ops:<chức năng>:action``. ROLE_ADMIN luôn qua mọi cổng có quyền nhân viên.
OPS_AI_POLICY_VIEW = "ops:ai-policy:view"
OPS_AI_POLICY_ACTION = "ops:ai-policy:action"
OPS_AI_SCORING_VIEW = "ops:ai-scoring:view"

_ACCESS_TOKEN_COOKIE = "access_token"
_LEEWAY_SECONDS = 5


def issuer() -> str:
    return os.getenv("KEYCLOAK_ISSUER_URI", "http://localhost:8180/realms/finora").rstrip("/")


def jwks_url() -> str:
    return os.getenv("KEYCLOAK_JWK_SET_URI", issuer() + "/protocol/openid-connect/certs")


@dataclass(frozen=True)
class Caller:
    """Người hoặc service đang gọi; chỉ giữ định danh và quyền, không giữ token."""

    subject: str
    client_id: str | None
    authorities: frozenset[str]
    # preferred_username của Keycloak, chỉ để ghi nhật ký kiểm toán cho dễ đọc; định danh chuẩn vẫn là subject.
    username: str | None = None

    def has_any(self, *names: str) -> bool:
        return any(name in self.authorities for name in names)


@lru_cache(maxsize=1)
def _jwks_client() -> jwt.PyJWKClient:
    # Cache khóa 5 phút; khóa mới (Keycloak xoay khóa) được tải lại khi gặp kid lạ.
    return jwt.PyJWKClient(jwks_url(), cache_keys=True, lifespan=300, timeout=5)


def _signing_key(token: str):
    return _jwks_client().get_signing_key_from_jwt(token).key


def authorities_of(claims: dict) -> frozenset[str]:
    roles: set[str] = set()
    for role in (claims.get("realm_access") or {}).get("roles", []) or []:
        name = str(role).upper()
        roles.add(name if name.startswith("ROLE_") else "ROLE_" + name)
    for access in (claims.get("resource_access") or {}).values():
        if isinstance(access, dict):
            roles.update(str(role) for role in access.get("roles", []) or [])
    return frozenset(roles)


def _unauthenticated(message: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail={"code": "AI_UNAUTHENTICATED", "message": message},
        headers={"WWW-Authenticate": "Bearer"},
    )


def _bearer_token(request: Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if header[:7].lower() == "bearer ":
        token = header[7:].strip()
        return token or None
    cookie = request.cookies.get(_ACCESS_TOKEN_COOKIE)
    return cookie.strip() if cookie else None


def current_caller(request: Request) -> Caller:
    """Kiểm token của request; không bao giờ log token."""
    token = _bearer_token(request)
    if token is None:
        raise _unauthenticated("Thiếu access token")
    try:
        key = _signing_key(token)
    except jwt.PyJWKClientConnectionError as exc:
        logger.warning("Không tải được JWKS Keycloak: %s", type(exc).__name__)
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                            detail={"code": "AI_AUTH_UNAVAILABLE",
                                    "message": "Chưa kiểm được access token, vui lòng thử lại"}) from exc
    except jwt.PyJWTError as exc:
        raise _unauthenticated("Access token không hợp lệ") from exc
    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            issuer=issuer(),
            leeway=_LEEWAY_SECONDS,
            # Access token của Keycloak có aud "account" hoặc không có: kiểm issuer + chữ ký + quyền là đủ.
            options={"require": ["exp", "iat", "iss", "sub"], "verify_aud": False},
        )
    except jwt.ExpiredSignatureError as exc:
        raise _unauthenticated("Access token đã hết hạn") from exc
    except jwt.PyJWTError as exc:
        raise _unauthenticated("Access token không hợp lệ") from exc
    return Caller(subject=str(claims["sub"]), client_id=claims.get("azp"), authorities=authorities_of(claims),
                  username=claims.get("preferred_username"))


def require_any(*authorities: str) -> Callable[[Caller], Caller]:
    """Dependency FastAPI: người gọi phải có ít nhất một quyền trong danh sách."""

    def dependency(caller: Caller = Depends(current_caller)) -> Caller:
        if not caller.has_any(*authorities):
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN,
                                detail={"code": "AI_FORBIDDEN", "message": "Không có quyền gọi chức năng này"})
        return caller

    return dependency


def api_docs_enabled() -> bool:
    """Swagger/OpenAPI chỉ mở ở môi trường phát triển và demo (D35, D37)."""
    return os.getenv("FINORA_ENVIRONMENT", "local").strip().lower() in {"local", "e2e", "demo"}
