"""
Kiểm access token Keycloak cho finora-ai.

Gateway của FINORA không kiểm JWT; mỗi service tự kiểm. Các service Java dùng Spring
Resource Server với `issuer-uri` + `jwk-set-uri`; module này làm đúng việc đó cho
FastAPI để luật giống nhau ở mọi nơi:

- Chữ ký RS256, khóa công khai lấy từ JWKS của realm `finora` (`KEYCLOAK_JWKS_URL`).
  Khóa được cache 10 phút; gặp `kid` lạ (Keycloak xoay khóa) thì PyJWT tự tải lại.
- `exp` bắt buộc, cho lệch đồng hồ 60 giây như `JwtTimestampValidator` của Spring.
- `iss` phải thuộc `KEYCLOAK_ISSUERS`. Là danh sách vì issuer phụ thuộc địa chỉ
  Keycloak được gọi; hiện local, docker và Azure đều cố định `http://localhost:8180`
  qua `KC_HOSTNAME_URL`, nhưng để danh sách thì đổi hạ tầng không phải sửa code.
- Không kiểm `aud`: token do `finora-user-client` cấp, service Java cũng không kiểm.

Vai trò đọc từ `realm_access.roles` và chuẩn hóa như `KeycloakJwtAuthenticationConverter`
(viết hoa, thêm tiền tố `ROLE_` nếu thiếu), nên `ROLE_ADMIN` ở đây và `hasRole` ở Java
là một. Token có thể mang nhiều vai trò vì `assignRole` chỉ thêm, không gỡ vai trò cũ.
"""

import os
from dataclasses import dataclass
from functools import lru_cache
from typing import Any

import jwt

MAC_DINH_JWKS_URL = "http://localhost:8180/realms/finora/protocol/openid-connect/certs"
MAC_DINH_ISSUERS = "http://localhost:8180/realms/finora"

# Lệch đồng hồ cho phép, bằng mặc định của Spring Security để hai bên cùng chấp nhận một token.
DO_LECH_DONG_HO_GIAY = 60
# Thời gian giữ bộ khóa JWKS trong bộ nhớ (spec mục 12: cache khóa 10 phút).
THOI_GIAN_GIU_JWKS_GIAY = 600
# JWKS nằm trong mạng nội bộ; chờ lâu hơn chỉ làm request của người dùng treo theo.
THOI_GIAN_CHO_JWKS_GIAY = 5

TIEN_TO_VAI_TRO = "ROLE_"


class AuthError(Exception):
    """Lỗi xác thực có mã: AUTH_REQUIRED | AUTH_INVALID | AUTH_EXPIRED | AUTH_UNAVAILABLE."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Principal:
    """Người đang gọi API, dựng từ access token đã kiểm.

    `token` giữ nguyên chuỗi gốc để trợ lý gọi tiếp service khác thay người dùng
    (relay); không bao giờ ghi log trường này.
    """

    user_id: str
    subject: str
    roles: frozenset[str]
    token: str
    expires_at: int

    def has_role(self, role: str) -> bool:
        """So khớp chính xác với tên đã chuẩn hóa, ví dụ `has_role("ROLE_ADMIN")`."""
        return role in self.roles


def extract_token(authorization: str | None, cookie_token: str | None) -> str | None:
    """Lấy access token như `DualBearerTokenResolver` của Java: Bearer trước, cookie sau.

    Mobile gửi `Authorization: Bearer ...`; web gửi cookie HttpOnly `access_token`. Có cả
    hai thì header thắng. Header không phải Bearer (ví dụ Basic) thì bỏ qua và xét cookie.
    """
    if authorization and authorization.startswith("Bearer "):
        token = authorization[len("Bearer ") :].strip()
        if token:
            return token
    if cookie_token and cookie_token.strip():
        return cookie_token.strip()
    return None


def _chuan_hoa_vai_tro(claims: dict[str, Any]) -> frozenset[str]:
    """`realm_access.roles` → tập tên dạng `ROLE_XXX`, bỏ qua claim sai kiểu."""
    realm_access = claims.get("realm_access")
    if not isinstance(realm_access, dict):
        return frozenset()
    roles = realm_access.get("roles")
    if not isinstance(roles, list):
        return frozenset()
    ket_qua: set[str] = set()
    for role in roles:
        ten = str(role).upper()
        ket_qua.add(ten if ten.startswith(TIEN_TO_VAI_TRO) else TIEN_TO_VAI_TRO + ten)
    return frozenset(ket_qua)


class KeycloakJwtVerifier:
    """Kiểm chữ ký, hạn và issuer của access token Keycloak.

    `jwks_client` để tiêm bản giả trong test (chỉ cần `get_signing_key_from_jwt`);
    mặc định là `jwt.PyJWKClient` tải JWKS lười ở lần kiểm đầu tiên, nên tạo đối tượng
    không gọi mạng.
    """

    def __init__(
        self,
        jwks_url: str,
        issuers: tuple[str, ...],
        jwks_client: "jwt.PyJWKClient | None" = None,
    ) -> None:
        if not issuers:
            raise ValueError("KEYCLOAK_ISSUERS phải có ít nhất một issuer")
        self.jwks_url = jwks_url
        self.issuers = issuers
        self._jwks_client = jwks_client or jwt.PyJWKClient(
            jwks_url,
            cache_keys=True,
            lifespan=THOI_GIAN_GIU_JWKS_GIAY,
            timeout=THOI_GIAN_CHO_JWKS_GIAY,
        )

    def verify(self, token: str) -> Principal:
        """Trả `Principal` khi token hợp lệ; ném `AuthError` với mã phân biệt được.

        Hàm đồng bộ vì PyJWKClient tải JWKS bằng urllib; bên gọi trong FastAPI chạy hàm
        này qua threadpool để lần tải khóa không chặn event loop.
        """
        try:
            khoa = self._jwks_client.get_signing_key_from_jwt(token)
        except jwt.PyJWKClientConnectionError as loi:
            raise AuthError(
                "AUTH_UNAVAILABLE", "Chưa kiểm được phiên đăng nhập, vui lòng thử lại sau."
            ) from loi
        except (jwt.PyJWKClientError, jwt.InvalidTokenError) as loi:
            raise AuthError("AUTH_INVALID", "Phiên đăng nhập không hợp lệ.") from loi

        try:
            claims = jwt.decode(
                token,
                khoa.key,
                algorithms=["RS256"],
                leeway=DO_LECH_DONG_HO_GIAY,
                # Issuer tự so khớp chính xác bên dưới để hỗ trợ danh sách; aud không kiểm
                # giống service Java.
                options={
                    "verify_aud": False,
                    "verify_iss": False,
                    "require": ["exp", "iss", "sub"],
                },
            )
        except jwt.ExpiredSignatureError as loi:
            raise AuthError("AUTH_EXPIRED", "Phiên đăng nhập đã hết hạn.") from loi
        except jwt.InvalidTokenError as loi:
            raise AuthError("AUTH_INVALID", "Phiên đăng nhập không hợp lệ.") from loi

        if claims.get("iss") not in self.issuers:
            raise AuthError("AUTH_INVALID", "Phiên đăng nhập không do FINORA cấp.")

        subject = str(claims["sub"])
        user_id = claims.get("user_id")
        return Principal(
            user_id=str(user_id) if user_id not in (None, "") else subject,
            subject=subject,
            roles=_chuan_hoa_vai_tro(claims),
            token=token,
            expires_at=int(claims["exp"]),
        )


@lru_cache(maxsize=1)
def get_verifier() -> KeycloakJwtVerifier:
    """Verifier dùng chung cả tiến trình, cấu hình qua môi trường.

    - `KEYCLOAK_JWKS_URL`: local `http://localhost:8180/...`; trong docker phải là tên
      nội bộ `http://keycloak:8080/realms/finora/protocol/openid-connect/certs`.
    - `KEYCLOAK_ISSUERS`: danh sách phân tách dấu phẩy, mặc định `http://localhost:8180/realms/finora`.
    """
    jwks_url = os.getenv("KEYCLOAK_JWKS_URL", MAC_DINH_JWKS_URL).strip()
    issuers = tuple(
        issuer.strip()
        for issuer in os.getenv("KEYCLOAK_ISSUERS", MAC_DINH_ISSUERS).split(",")
        if issuer.strip()
    )
    return KeycloakJwtVerifier(jwks_url, issuers)
