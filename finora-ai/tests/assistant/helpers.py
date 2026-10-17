"""Hàm dựng dữ liệu giả dùng chung cho test của trợ lý.

Gọi HTTP giả bằng `httpx.MockTransport` (spec mục 15) thay vì pytest-httpx: transport được
truyền thẳng vào `FinoraApiClient`, nên một test nhìn thấy toàn bộ request mà tool đã gửi
(kể cả request đi sai URL) mà không phải bật fixture toàn cục.
"""

from __future__ import annotations

import time
from collections.abc import Mapping
from typing import TYPE_CHECKING, Any

import httpx

from app.services.assistant.principal import Toolset
from app.services.assistant.settings import AssistantSettings
from app.services.auth.keycloak_jwt import Principal

if TYPE_CHECKING:
    from app.services.assistant.tools.base import ToolResult, ToolSpec

TEST_URLS: dict[str, str] = {
    "loan": "http://loan.test",
    "payment": "http://payment.test",
    "investment": "http://investment.test",
    "user": "http://user.test",
    "notification": "http://notification.test",
}

RouteMap = Mapping[tuple[str, str], tuple[int, Any]]


def make_settings(**overrides: Any) -> AssistantSettings:
    """Cấu hình bật trợ lý, đủ ba vai trò, URL service trỏ về các host `*.test`."""
    values: dict[str, Any] = {
        "enabled": True,
        "toolsets": frozenset(Toolset),
        "provider_order": ("groq_gptoss120b",),
        "public_extra_providers": (),
        "hash_salt": "test-salt",
        "loan_url": TEST_URLS["loan"],
        "payment_url": TEST_URLS["payment"],
        "investment_url": TEST_URLS["investment"],
        "user_url": TEST_URLS["user"],
        "notification_url": TEST_URLS["notification"],
    }
    values.update(overrides)
    return AssistantSettings(**values)


def make_principal(
    *roles: str, user_id: str = "u-1", exp_in: int = 600, token: str = "tok"
) -> Principal:
    """Principal đã xác thực với các realm role cho trước, hết hạn sau `exp_in` giây."""
    return Principal(
        user_id=user_id,
        subject=user_id,
        roles=frozenset(roles),
        token=token,
        expires_at=int(time.time()) + exp_in,
    )


class RecordingTransport(httpx.MockTransport):
    """MockTransport ghi lại mọi request và các request không có trong bảng route."""

    def __init__(self, routes: RouteMap) -> None:
        self.requests: list[httpx.Request] = []
        self.unmatched: list[tuple[str, str]] = []
        self._routes = dict(routes)
        super().__init__(self._handle)

    def _handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        key = (request.method, str(request.url).split("?", 1)[0])
        if key not in self._routes:
            self.unmatched.append(key)
            return httpx.Response(599, json={"code": "TEST_ROUTE_NOT_DECLARED"})
        status, body = self._routes[key]
        if isinstance(body, BaseException):
            raise body
        if isinstance(body, bytes):
            return httpx.Response(status, content=body)
        if body is None:
            return httpx.Response(status)
        return httpx.Response(status, json=body)


def route_transport(routes: RouteMap) -> RecordingTransport:
    """Transport giả theo bảng `(METHOD, URL không query) -> (status, body)`.

    `body` là JSON (dict/list/số/chuỗi); `bytes` để giả JSON hỏng; `None` cho body rỗng; một
    exception (ví dụ `httpx.ReadTimeout("x")`) để giả lỗi mạng/timeout. Request không khai báo
    nhận 599 và được ghi vào `transport.unmatched`.
    """
    return RecordingTransport(routes)


async def run_tool(spec: ToolSpec, params: dict[str, Any], routes: RouteMap) -> ToolResult:
    """Chạy một tool như orchestrator: kiểm tham số bằng `params_model`, gọi handler qua relay.

    `params` viết camelCase như LLM gửi. Request đi ra ngoài bảng `routes` làm test hỏng ngay
    (AssertionError) thay vì bị nuốt thành ToolError, để sai URL không lặng lẽ thành "không
    lấy được dữ liệu".
    """
    # Import trong hàm: Task 5 tạo helper này trước khi relay (Task 6) tồn tại.
    from app.services.assistant.relay import FinoraApiClient

    transport = route_transport(routes)
    validated = spec.params_model.model_validate(params)
    try:
        async with FinoraApiClient(
            make_settings(), "tok", spec.routes, transport=transport
        ) as client:
            return await spec.handler(client, validated)
    finally:
        if transport.unmatched:
            raise AssertionError(f"Tool {spec.name} gọi URL chưa khai báo: {transport.unmatched}")
