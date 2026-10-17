"""
Lỗi API có mã của finora-ai và bộ xử lý trả JSON `{"code", "message"}`.

Các service Java của FINORA trả lỗi dạng `ApiErrorResponse` có `code` và `message`;
web (`toUiApiError`) và mobile (`ApiError.code`) đã đọc đúng hai trường đó. Lỗi mới của
finora-ai (xác thực, trợ lý) đi theo cùng khuôn để client không phải viết thêm nhánh.

Lỗi cũ dùng `HTTPException(detail=...)` (cấu hình luật, nạp model) giữ nguyên: web đã có
`toAiUiError` đọc `detail`, đổi khuôn lúc này sẽ làm vỡ màn hình đang chạy.
"""

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse


class ApiError(Exception):
    """Lỗi nghiệp vụ có mã ổn định, ví dụ 401 `AUTH_REQUIRED`, 403 `ADMIN_ROLE_REQUIRED`.

    `code` là thứ client dùng để rẽ nhánh nên không được đổi tùy tiện; `message` là câu
    tiếng Việt hiển thị cho người dùng.
    """

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


async def _tra_api_error(request: Request, exc: ApiError) -> JSONResponse:
    """Đổi `ApiError` thành JSON; 401 kèm `WWW-Authenticate` như chuẩn Bearer (RFC 6750)."""
    headers = {"WWW-Authenticate": "Bearer"} if exc.status_code == 401 else None
    return JSONResponse(
        status_code=exc.status_code,
        content={"code": exc.code, "message": exc.message},
        headers=headers,
    )


def register_error_handlers(app: FastAPI) -> None:
    """Đăng ký bộ xử lý `ApiError` cho app; gọi một lần ngay sau khi tạo `FastAPI()`."""
    app.add_exception_handler(ApiError, _tra_api_error)
