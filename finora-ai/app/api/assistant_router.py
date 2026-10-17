"""API của Trợ lý FINORA: POST /chat, GET /suggestions, POST /feedback (prefix /api/v1/ai/assistant).

Router chỉ làm việc "cửa ngõ": công tắc bật/tắt, xác thực token, chọn bộ công cụ theo vai trò và nơi
gửi, hạn mức theo người dùng. Mọi logic trả lời nằm trong AssistantOrchestrator để test được mà không
cần HTTP. Các phụ thuộc đi qua `Depends` để test thay bằng bản giả qua `app.dependency_overrides`.
"""

import time
from functools import lru_cache
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Request, Response

from app.api.errors import ApiError
from app.schemas.assistant import (
    ChatRequest,
    ChatResponse,
    FeedbackRequest,
    SuggestionItem,
)
from app.services.assistant.audit import hash_user, log_feedback
from app.services.assistant.llm.quota import UserRateLimiter
from app.services.assistant.orchestrator import AssistantOrchestrator, get_orchestrator
from app.services.assistant.principal import Toolset, resolve_toolset
from app.services.assistant.settings import AssistantSettings, get_settings
from app.services.auth.keycloak_jwt import (
    AuthError,
    KeycloakJwtVerifier,
    Principal,
    extract_token,
    get_verifier,
)

router = APIRouter()

# Một lượt có thể kéo dài tới 40 giây và token được dùng lại để gọi Loan/Investment/Payment;
# token còn dưới 60 giây thì bắt app làm mới trước, tránh service đích trả 401 giữa chừng.
TOKEN_CON_TOI_THIEU_S = 60


@lru_cache(maxsize=1)
def get_user_rate_limiter() -> UserRateLimiter:
    """Một bộ đếm hạn mức cho cả tiến trình (uvicorn một worker, spec mục 11)."""
    settings = get_settings()
    return UserRateLimiter(settings.user_rate_per_minute, settings.user_rate_per_day)


SettingsDep = Annotated[AssistantSettings, Depends(get_settings)]


def require_assistant_enabled(settings: SettingsDep) -> AssistantSettings:
    """Công tắc tổng; kiểm trước cả token để web/mobile ẩn nút chat khi nhận 503."""
    if not settings.enabled:
        raise ApiError(503, "ASSISTANT_DISABLED", "Trợ lý FINORA đang tạm tắt.")
    return settings


def require_assistant_principal(
    request: Request,
    _settings: Annotated[AssistantSettings, Depends(require_assistant_enabled)],
    verifier: Annotated[KeycloakJwtVerifier, Depends(get_verifier)],
) -> Principal:
    """Người gọi đã đăng nhập và token còn đủ lâu; lỗi xác thực đều là 401 ASSISTANT_UNAUTHENTICATED.

    Hàm đồng bộ để FastAPI chạy trong threadpool: lần đầu `verify` tải JWKS của Keycloak bằng I/O
    chặn, không được chặn event loop đang phục vụ các lượt chat khác.
    """
    token = extract_token(
        request.headers.get("Authorization"), request.cookies.get("access_token")
    )
    if not token:
        raise ApiError(401, "ASSISTANT_UNAUTHENTICATED", "Bạn cần đăng nhập để dùng trợ lý.")
    try:
        principal = verifier.verify(token)
    except AuthError as loi:
        if loi.code == "AUTH_UNAVAILABLE":
            # Chưa tải được khóa của Keycloak: lỗi tạm thời phía máy chủ, không bắt đăng nhập lại.
            raise ApiError(
                503, "ASSISTANT_AUTH_UNAVAILABLE", "Trợ lý tạm gián đoạn, bạn thử lại sau ít phút."
            ) from loi
        raise ApiError(
            401, "ASSISTANT_UNAUTHENTICATED", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."
        ) from loi
    if principal.expires_at - time.time() < TOKEN_CON_TOI_THIEU_S:
        raise ApiError(
            401, "ASSISTANT_UNAUTHENTICATED", "Phiên đăng nhập sắp hết hạn, ứng dụng sẽ tự làm mới."
        )
    return principal


PrincipalDep = Annotated[Principal, Depends(require_assistant_principal)]
OrchestratorDep = Annotated[AssistantOrchestrator, Depends(get_orchestrator)]


def _bo_cong_cu(
    principal: Principal, surface: str, settings: AssistantSettings
) -> frozenset[Toolset]:
    toolsets = resolve_toolset(principal, surface, settings.toolsets)
    if not toolsets:
        raise ApiError(
            403, "ASSISTANT_ROLE_NOT_SUPPORTED", "Tài khoản này chưa dùng được trợ lý ở đây."
        )
    return toolsets


@router.post("/chat", response_model=ChatResponse)
async def chat(
    body: ChatRequest,
    principal: PrincipalDep,
    settings: SettingsDep,
    limiter: Annotated[UserRateLimiter, Depends(get_user_rate_limiter)],
    orchestrator: OrchestratorDep,
) -> ChatResponse:
    toolsets = _bo_cong_cu(principal, body.surface, settings)
    # Đếm hạn mức sau khi biết vai trò hợp lệ: request bị 403 không ăn vào hạn mức người dùng.
    if not limiter.try_acquire(hash_user(principal.user_id, settings.hash_salt)):
        raise ApiError(429, "ASSISTANT_RATE_LIMITED", "Bạn hỏi hơi nhanh, thử lại sau ít phút.")
    return await orchestrator.handle(principal, toolsets, body)


@router.get("/suggestions", response_model=list[SuggestionItem])
async def suggestions(
    surface: Annotated[Literal["mobile", "web"], Query()],
    principal: PrincipalDep,
    settings: SettingsDep,
    orchestrator: OrchestratorDep,
    screen: Annotated[str | None, Query(max_length=40)] = None,
) -> list[SuggestionItem]:
    """Gợi ý theo vai trò và màn hình; web/mobile chỉ hiện nút chat khi endpoint này trả 200."""
    return orchestrator.suggestions(_bo_cong_cu(principal, surface, settings), screen)


@router.post("/feedback", status_code=204, response_class=Response)
async def feedback(
    body: FeedbackRequest, principal: PrincipalDep, settings: SettingsDep
) -> Response:
    """Chỉ ghi log siêu dữ liệu (turnId, người đã băm, up/down); không lưu nội dung lượt."""
    log_feedback(
        turn_id=body.turn_id,
        user_hash=hash_user(principal.user_id, settings.hash_salt),
        rating=body.rating,
    )
    return Response(status_code=204)
