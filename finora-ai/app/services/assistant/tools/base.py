"""Kiểu dùng chung của công cụ trợ lý: khai báo tool, kết quả, lỗi, gợi ý và allowlist route.

Mỗi tool là một hàm chỉ đọc gọi service nghiệp vụ bằng token của người hỏi. Khai báo bằng
dataclass (không phải class con) để ba file vai trò chỉ là danh sách dữ liệu, dễ đọc và dễ đối
chiếu với bảng ở spec mục 8.
"""

from __future__ import annotations

import re
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any, Literal

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from app.schemas.assistant import Action, Card, Citation
from app.services.assistant.llm.providers import Lane
from app.services.assistant.principal import Toolset

if TYPE_CHECKING:
    # Chỉ dùng cho chú thích kiểu: relay import ngược file này (RouteRule, ToolError) lúc chạy.
    from app.services.assistant.relay import FinoraApiClient, ServiceName

# Tên function theo giới hạn của API kiểu OpenAI (Groq/Cloudflare/OpenRouter).
_TEN_TOOL = re.compile(r"[a-z][a-z0-9_]{1,63}")


@dataclass(frozen=True)
class RouteRule:
    """Một cặp (method, service, mẫu path) mà tool được phép gọi.

    `pattern` so bằng `re.fullmatch` trên path không có query, ví dụ
    `r"/api/v1/loan-applications/LA-[A-Z0-9]{6,32}"`. Relay từ chối mọi request không khớp
    một rule của các tool thuộc vai trò đang hỏi, nên một tool lỗi hoặc tham số bị LLM bẻ
    cũng không đọc được endpoint ngoài danh sách.
    """

    method: Literal["GET", "POST"]
    service: ServiceName
    pattern: str

    def __post_init__(self) -> None:
        if self.method not in ("GET", "POST"):
            raise ValueError(f"Trợ lý chỉ đọc, method không hỗ trợ: {self.method}")
        # Biên dịch sớm để pattern sai lộ ra lúc import chứ không phải giữa một lượt hỏi.
        re.compile(self.pattern)

    def matches(self, method: str, service: ServiceName, path: str) -> bool:
        return (
            self.method == method
            and self.service == service
            and re.fullmatch(self.pattern, path) is not None
        )


class ToolError(Exception):
    """Lỗi có câu thông báo an toàn để hiện cho người dùng.

    `code`: `PHIEN_HET_HAN` | `KHONG_TIM_THAY` | `DICH_VU_LOI` | `DU_LIEU_KHONG_HOP_LE` |
    `KHONG_DUOC_PHEP` (relay) hoặc mã riêng của tool. `upstream_code` là mã lỗi service đích trả
    về (ví dụ `CORE_LENDING_UNAVAILABLE`) để glossary dịch, có thể None.
    """

    def __init__(self, code: str, user_message: str, *, upstream_code: str | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.user_message = user_message
        self.upstream_code = upstream_code


@dataclass
class ToolResult:
    """Kết quả một tool: dữ kiện cho LLM tách khỏi phần hiển thị.

    `facts` chỉ chứa trường đã lọc theo whitelist và chuỗi đã định dạng (không thu nhập, không
    định danh, không trường tự do). `ids` là các mã nghiệp vụ tool đã thấy trong lượt, để
    orchestrator từ chối mã do LLM tự bịa ra.
    """

    facts: dict[str, Any]
    cards: list[Card] = field(default_factory=list)
    actions: list[Action] = field(default_factory=list)
    citations: list[Citation] = field(default_factory=list)
    ids: frozenset[str] = frozenset()


class ToolParams(BaseModel):
    """Gốc cho tham số tool: LLM thấy và gửi camelCase, khóa thừa bị từ chối."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class NoParams(ToolParams):
    """Tool không nhận tham số; LLM gửi kèm khóa nào cũng bị từ chối (extra="forbid")."""


@dataclass(frozen=True)
class ToolSpec:
    """Khai báo một công cụ."""

    name: str
    toolset: Toolset
    description: str  # tiếng Việt, gửi cho LLM
    params_model: type[BaseModel]
    handler: Callable[[FinoraApiClient, Any], Awaitable[ToolResult]]
    lane: Lane
    routes: tuple[RouteRule, ...]
    slow: bool = False  # True: đọc Fineract, dùng timeout dài (`slow_tool_timeout_s`)

    def __post_init__(self) -> None:
        if not _TEN_TOOL.fullmatch(self.name):
            raise ValueError(f"Tên tool không hợp lệ: {self.name}")

    def openai_schema(self) -> dict[str, Any]:
        """Khai báo function theo định dạng chat completions kiểu OpenAI."""
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": _lam_gon_schema(
                    self.params_model.model_json_schema(by_alias=True), self.name
                ),
            },
        }


@dataclass(frozen=True)
class SuggestionDef:
    """Nút gợi ý: chạy một tool cố định rồi dựng câu trả lời bằng mẫu, không gọi LLM."""

    id: str
    label: str
    toolset: Toolset
    screens: frozenset[str]  # rỗng = mọi màn
    tool: str  # tên ToolSpec
    params_from_context: Mapping[str, str]  # tham số tool <- khóa context.params
    render: Callable[[ToolResult], str]  # câu mẫu
    keywords: tuple[str, ...] = ()  # cho intents.match_intent
    # Gợi ý liệt kê (không cần mã) nhưng câu hỏi/màn đã có đúng một mã đối tượng: dùng gợi ý cụ thể này thay,
    # để "Hồ sơ LA-… đang ở bước nào?" trả lời đúng hồ sơ đó chứ không phải hồ sơ mới nhất (fix-2).
    khi_co_ma: str | None = None


# (tên tool, tham số tool <- khóa context.params): tool "lấy dữ liệu màn hình" của đường B.
# Tham số tùy chọn của tool (mã khoản vay/hợp đồng đang mở, trạng thái tab) không cần khai ở đây: orchestrator tự
# điền từ context.params khi tên khóa trùng tên tham số (fix-2).
ScreenTool = tuple[str, Mapping[str, str]]


def _lam_gon_schema(schema: dict[str, Any], tool_name: str) -> dict[str, Any]:
    """Rút JSON Schema của Pydantic về dạng phẳng mà mọi nhà cung cấp đều đọc được.

    Bỏ `title` và docstring của class tham số (tốn token; mô tả tool đã nằm ở `description` của
    function), đổi `anyOf: [X, null]` của trường tùy chọn thành X (vài nhà, như Gemini, đọc
    `anyOf` kém; trường đã không nằm trong `required` nên model vẫn được bỏ qua). Không cho
    `$defs`/`$ref`: tham số tool phải phẳng.
    """
    if "$defs" in schema or "definitions" in schema:
        raise ValueError(f"Tham số của tool {tool_name} phải phẳng (không model lồng/Enum class)")
    result = {key: value for key, value in schema.items() if key not in ("title", "description")}
    result["type"] = "object"
    result["properties"] = {
        name: _lam_gon_thuoc_tinh(prop) for name, prop in schema.get("properties", {}).items()
    }
    return result


def _lam_gon_thuoc_tinh(prop: dict[str, Any]) -> dict[str, Any]:
    result = {key: value for key, value in prop.items() if key != "title"}
    any_of = result.get("anyOf")
    if isinstance(any_of, list):
        non_null = [option for option in any_of if option != {"type": "null"}]
        if len(non_null) == 1 and len(non_null) < len(any_of):
            result.pop("anyOf")
            if "default" in result and result["default"] is None:
                result.pop("default")
            result = {**non_null[0], **result}
    return result
