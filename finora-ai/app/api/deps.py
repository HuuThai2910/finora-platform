"""
Dependency xác thực dùng chung cho các router của finora-ai.

- `require_principal`: mọi API cần biết ai đang gọi (trợ lý).
- `require_admin`: thao tác chỉ quản trị được làm (sửa cấu hình chấm điểm).
- `require_internal_or_admin`: API vừa do service khác gọi (Loan chấm điểm hồ sơ),
  vừa do admin gọi từ web (trang chấm thử). Loan không có JWT của người dùng khi worker
  chấm điểm chạy nền, nên nó chứng minh danh tính bằng mã nội bộ `X-Internal-Token`.

Lỗi trả `ApiError` để client nhận `{"code", "message"}` như service Java.
"""

import hmac
import logging
import os

from fastapi import Depends, Request
from starlette.concurrency import run_in_threadpool

from app.api.errors import ApiError
from app.services.auth.keycloak_jwt import (
    AuthError,
    Principal,
    extract_token,
    get_verifier,
)

logger = logging.getLogger(__name__)

COOKIE_ACCESS_TOKEN = "access_token"
HEADER_NOI_BO = "X-Internal-Token"
VAI_TRO_ADMIN = "ROLE_ADMIN"


def _api_error_tu(loi: AuthError) -> ApiError:
    """Không lấy được khóa Keycloak là lỗi tạm của hệ thống (503), còn lại là 401."""
    if loi.code == "AUTH_UNAVAILABLE":
        return ApiError(503, loi.code, loi.message)
    return ApiError(401, loi.code, loi.message)


async def require_principal(request: Request) -> Principal:
    """Kiểm access token (Bearer trước, cookie `access_token` sau) và trả người gọi."""
    token = extract_token(
        request.headers.get("Authorization"),
        request.cookies.get(COOKIE_ACCESS_TOKEN),
    )
    if token is None:
        raise ApiError(401, "AUTH_REQUIRED", "Bạn cần đăng nhập để dùng chức năng này.")
    try:
        # verify() có thể tải JWKS qua mạng bằng urllib (đồng bộ): chạy trong threadpool
        # để một lần Keycloak chậm không làm đứng mọi request khác của tiến trình.
        return await run_in_threadpool(get_verifier().verify, token)
    except AuthError as loi:
        raise _api_error_tu(loi) from loi


async def require_admin(principal: Principal = Depends(require_principal)) -> Principal:
    """Chỉ cho `ROLE_ADMIN`; mã và câu lỗi giống `SecurityUtils.requireAdmin()` của Java."""
    if not principal.has_role(VAI_TRO_ADMIN):
        raise ApiError(
            403,
            "ADMIN_ROLE_REQUIRED",
            "Tài khoản không có quyền quản trị để thực hiện thao tác này",
        )
    return principal


async def require_internal_or_admin(request: Request) -> None:
    """Cho qua khi có `X-Internal-Token` đúng, hoặc khi người gọi là admin.

    Có gửi header nội bộ thì chỉ xét header: sai là 401 `INTERNAL_TOKEN_INVALID` ngay,
    không rơi xuống kiểm JWT, để Loan cấu hình sai thấy đúng nguyên nhân trong log thay vì
    một lỗi "chưa đăng nhập" khó hiểu. `AI_INTERNAL_TOKEN` chưa đặt thì mọi header nội bộ
    đều bị từ chối (đóng mặc định), vì endpoint này mở ra Internet qua gateway.
    """
    gui_len = request.headers.get(HEADER_NOI_BO)
    if gui_len is not None:
        ma_dung = os.getenv("AI_INTERNAL_TOKEN", "").strip()
        if not ma_dung:
            logger.warning("Từ chối %s: AI_INTERNAL_TOKEN chưa được cấu hình", HEADER_NOI_BO)
        elif hmac.compare_digest(gui_len.strip().encode("utf-8"), ma_dung.encode("utf-8")):
            return
        raise ApiError(401, "INTERNAL_TOKEN_INVALID", "Mã xác thực nội bộ không hợp lệ.")

    principal = await require_principal(request)
    await require_admin(principal)
