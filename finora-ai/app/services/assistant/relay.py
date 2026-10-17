"""Gọi service nghiệp vụ thay người dùng, bằng chính access token của họ.

Trợ lý không có service account: Loan/Investment/Payment/User/Notification tự kiểm chủ sở
hữu (`requireOwner`, `hasRole`) như khi app gọi trực tiếp, nên bot không thể đọc dữ liệu
người khác dù LLM có bị dụ. Thêm một lớp chặn ở đây: chỉ gửi request khớp allowlist
`RouteRule` của các tool thuộc vai trò đang hỏi.

Gọi thẳng service trong mạng docker, không qua gateway (profile docker của gateway thiếu vài
route — spec mục 3). Mọi lỗi đổi thành `ToolError` có câu an toàn; log chỉ ghi service, mẫu
route, status, độ trễ — không ghi token, path thật (có mã hồ sơ) hay body.
"""

import logging
import time
from collections.abc import Mapping, Sequence
from enum import Enum
from types import TracebackType
from typing import Any, Self

import httpx

from app.services.assistant.settings import AssistantSettings
from app.services.assistant.tools.base import RouteRule, ToolError

logger = logging.getLogger(__name__)

MSG_PHIEN_HET_HAN = "Phiên đăng nhập đã hết hạn, bạn đăng nhập lại giúp mình nhé."
MSG_KHONG_TIM_THAY = "Không tìm thấy dữ liệu bạn hỏi."
MSG_DICH_VU_LOI = "Chưa lấy được dữ liệu, bạn thử lại sau ít phút."
MSG_LICH_DANG_CHUAN_BI = "Hệ thống đang chuẩn bị lịch trả nợ, bạn thử lại sau khoảng 30 giây."
MSG_DU_LIEU_KHONG_HOP_LE = "Dữ liệu nhận được không hợp lệ, mình chưa trả lời được câu này."
MSG_KHONG_DUOC_PHEP = "Trợ lý không được phép đọc dữ liệu này."


class ServiceName(str, Enum):
    LOAN = "loan"
    PAYMENT = "payment"
    INVESTMENT = "investment"
    USER = "user"
    NOTIFICATION = "notification"


class FinoraApiClient:
    """Client HTTP của một lượt hỏi. Tạo mới mỗi lượt vì token thuộc về người hỏi."""

    def __init__(
        self,
        settings: AssistantSettings,
        token: str,
        allowed: Sequence[RouteRule],
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_urls: dict[ServiceName, str] = {
            ServiceName.LOAN: settings.loan_url,
            ServiceName.PAYMENT: settings.payment_url,
            ServiceName.INVESTMENT: settings.investment_url,
            ServiceName.USER: settings.user_url,
            ServiceName.NOTIFICATION: settings.notification_url,
        }
        self._allowed = tuple(allowed)
        self._timeout = settings.tool_timeout_s
        self._slow_timeout = settings.slow_tool_timeout_s
        self._http = httpx.AsyncClient(
            transport=transport,
            headers={
                "Authorization": f"Bearer {token}",
                # Cho service đích (và log của nó) biết request đến từ trợ lý, không phải app.
                "X-Client-Type": "assistant",
                "Accept": "application/json",
            },
            # Service trả chuyển hướng là cấu hình sai; không đi theo để token không rời mạng nội bộ.
            follow_redirects=False,
        )

    async def get(
        self,
        service: ServiceName,
        path: str,
        params: Mapping[str, Any] | None = None,
        *,
        slow: bool = False,
    ) -> Any:
        """GET JSON. `params` có giá trị None bị bỏ để tool truyền thẳng bộ lọc tùy chọn."""
        query = {key: value for key, value in (params or {}).items() if value is not None}
        return await self._send("GET", service, path, query=query, body=None, slow=slow)

    async def post(
        self,
        service: ServiceName,
        path: str,
        json: Mapping[str, Any],
        *,
        slow: bool = False,
    ) -> Any:
        """POST JSON — chỉ dùng cho API tính toán không ghi dữ liệu (mô phỏng lịch trả)."""
        return await self._send("POST", service, path, query=None, body=dict(json), slow=slow)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.aclose()

    async def _send(
        self,
        method: str,
        service: ServiceName,
        path: str,
        *,
        query: Mapping[str, Any] | None,
        body: dict[str, Any] | None,
        slow: bool,
    ) -> Any:
        rule = self._match(method, service, path)
        url = self._base_urls[service] + path
        timeout = self._slow_timeout if slow else self._timeout
        started = time.monotonic()
        try:
            response = await self._http.request(method, url, params=query, json=body, timeout=timeout)
        except httpx.TimeoutException as exc:
            self._log(logging.WARNING, method, service, rule, "timeout", started)
            raise ToolError("DICH_VU_LOI", MSG_DICH_VU_LOI) from exc
        except httpx.HTTPError as exc:
            self._log(logging.WARNING, method, service, rule, f"network:{type(exc).__name__}", started)
            raise ToolError("DICH_VU_LOI", MSG_DICH_VU_LOI) from exc

        status = response.status_code
        if 200 <= status < 300:
            return self._read_json(response, method, service, rule, started)

        upstream_code = _error_code(response)
        if status == 401:
            self._log(logging.INFO, method, service, rule, "401", started)
            raise ToolError("PHIEN_HET_HAN", MSG_PHIEN_HET_HAN, upstream_code=upstream_code)
        if status in (403, 404):
            # Cùng một câu cho 403 và 404 để không lộ hồ sơ của người khác có tồn tại hay không.
            self._log(logging.INFO, method, service, rule, str(status), started)
            raise ToolError("KHONG_TIM_THAY", MSG_KHONG_TIM_THAY, upstream_code=upstream_code)
        if status >= 500:
            self._log(logging.WARNING, method, service, rule, str(status), started)
            message = MSG_LICH_DANG_CHUAN_BI if upstream_code == "CORE_LENDING_UNAVAILABLE" else MSG_DICH_VU_LOI
            raise ToolError("DICH_VU_LOI", message, upstream_code=upstream_code)
        self._log(logging.WARNING, method, service, rule, str(status), started)
        raise ToolError("DU_LIEU_KHONG_HOP_LE", MSG_DU_LIEU_KHONG_HOP_LE, upstream_code=upstream_code)

    def _match(self, method: str, service: ServiceName, path: str) -> RouteRule:
        # Query phải đi qua `params`; path chứa "?" hoặc "#" bị chặn để rule không bị lách.
        if "?" not in path and "#" not in path:
            for rule in self._allowed:
                if rule.matches(method, service, path):
                    return rule
        # Không log path: nó có thể chứa mã do LLM bịa hoặc chuỗi chèn lệnh.
        logger.warning(
            "assistant relay method=%s service=%s result=blocked_not_allowlisted",
            method, service.value,
        )
        raise ToolError("KHONG_DUOC_PHEP", MSG_KHONG_DUOC_PHEP)

    def _read_json(
        self,
        response: httpx.Response,
        method: str,
        service: ServiceName,
        rule: RouteRule,
        started: float,
    ) -> Any:
        if response.status_code == 204 or not response.content:
            self._log(logging.INFO, method, service, rule, str(response.status_code), started)
            return None
        try:
            data = response.json()
        except ValueError as exc:
            self._log(logging.WARNING, method, service, rule, "invalid_json", started)
            raise ToolError("DU_LIEU_KHONG_HOP_LE", MSG_DU_LIEU_KHONG_HOP_LE) from exc
        self._log(logging.INFO, method, service, rule, str(response.status_code), started)
        return data

    @staticmethod
    def _log(
        level: int,
        method: str,
        service: ServiceName,
        rule: RouteRule,
        result: str,
        started: float,
    ) -> None:
        logger.log(
            level,
            "assistant relay method=%s service=%s route=%s result=%s latency_ms=%.0f",
            method, service.value, rule.pattern, result, (time.monotonic() - started) * 1000,
        )


def _error_code(response: httpx.Response) -> str | None:
    """Đọc `code` của `ApiErrorResponse` (Java) nếu có; body lạ thì None."""
    try:
        data = response.json()
    except ValueError:
        return None
    code = data.get("code") if isinstance(data, dict) else None
    return code if isinstance(code, str) and len(code) <= 80 else None
