"""Danh bạ công cụ, gợi ý và tool màn hình của cả ba vai trò.

Lọc theo bộ công cụ của người hỏi ở đây, một chỗ duy nhất: LLM chỉ thấy tool của vai trò mình
(4–8 tool, tiết kiệm token) và relay chỉ nhận route của các tool đó. Khai báo sai (trùng tên,
gợi ý trỏ tool không có, khác vai trò) bị bắt lúc dựng registry, tức lúc khởi động service.
"""

from collections.abc import Iterable, Mapping
from functools import lru_cache
from itertools import chain, zip_longest

from app.services.assistant.principal import Toolset
from app.services.assistant.tools import admin, borrower, investor
from app.services.assistant.tools.base import (
    RouteRule,
    ScreenTool,
    SuggestionDef,
    ToolSpec,
)

MAX_SUGGESTIONS = 4

# Thứ tự cố định khi một người có nhiều vai trò, để gợi ý không đổi chỗ giữa các lần mở.
_TOOLSET_ORDER: tuple[Toolset, ...] = (Toolset.BORROWER, Toolset.INVESTOR, Toolset.ADMIN)


class ToolRegistry:
    def __init__(
        self,
        specs: Iterable[ToolSpec],
        suggestions: Iterable[SuggestionDef] = (),
        screen_tools: Mapping[str, ScreenTool] | None = None,
    ) -> None:
        self._specs: dict[str, ToolSpec] = {}
        for spec in specs:
            if spec.name in self._specs:
                raise ValueError(f"Trùng tên tool: {spec.name}")
            self._specs[spec.name] = spec

        self._suggestions: dict[str, SuggestionDef] = {}
        for suggestion in suggestions:
            if suggestion.id in self._suggestions:
                raise ValueError(f"Trùng id gợi ý: {suggestion.id}")
            spec = self._specs.get(suggestion.tool)
            if spec is None:
                raise ValueError(f"Gợi ý {suggestion.id} trỏ tới tool chưa khai báo: {suggestion.tool}")
            if spec.toolset != suggestion.toolset:
                raise ValueError(f"Gợi ý {suggestion.id} khác vai trò với tool {spec.name}")
            self._suggestions[suggestion.id] = suggestion

        # Một màn có thể có tool riêng cho từng vai trò (ví dụ "Home" của người vay và nhà đầu tư).
        self._screen_tools: dict[str, list[ScreenTool]] = {}
        self.add_screen_tools(screen_tools or {})

    def add_screen_tools(self, screen_tools: Mapping[str, ScreenTool]) -> None:
        """Thêm tool màn hình; dùng khi gộp bảng của nhiều vai trò có màn trùng tên."""
        for screen, screen_tool in screen_tools.items():
            tool_name = screen_tool[0]
            spec = self._specs.get(tool_name)
            if spec is None:
                raise ValueError(f"Màn {screen} trỏ tới tool chưa khai báo: {tool_name}")
            existing = self._screen_tools.setdefault(screen, [])
            if any(self._specs[name].toolset == spec.toolset for name, _ in existing):
                raise ValueError(f"Màn {screen} đã có tool màn hình cho vai trò {spec.toolset.value}")
            existing.append(screen_tool)

    def for_toolsets(self, toolsets: frozenset[Toolset]) -> list[ToolSpec]:
        """Tool của các vai trò, theo thứ tự khai báo."""
        return [spec for spec in self._specs.values() if spec.toolset in toolsets]

    def get(self, name: str) -> ToolSpec | None:
        return self._specs.get(name)

    def routes_for(self, toolsets: frozenset[Toolset]) -> list[RouteRule]:
        """Allowlist cho relay: route của mọi tool thuộc các vai trò, bỏ trùng."""
        routes: list[RouteRule] = []
        for spec in self.for_toolsets(toolsets):
            for rule in spec.routes:
                if rule not in routes:
                    routes.append(rule)
        return routes

    def suggestions_for(self, toolsets: frozenset[Toolset], screen: str | None) -> list[SuggestionDef]:
        """Tối đa 4 gợi ý: gợi ý riêng của màn trước, gợi ý chung sau.

        Trong mỗi nhóm xen kẽ theo vai trò (người vay, nhà đầu tư, quản trị) để người có hai vai
        trò thấy gợi ý của cả hai thay vì bốn gợi ý của vai trò khai báo trước.
        """
        specific: dict[Toolset, list[SuggestionDef]] = {toolset: [] for toolset in _TOOLSET_ORDER}
        general: dict[Toolset, list[SuggestionDef]] = {toolset: [] for toolset in _TOOLSET_ORDER}
        for suggestion in self._suggestions.values():
            if suggestion.toolset not in toolsets:
                continue
            if not suggestion.screens:
                general[suggestion.toolset].append(suggestion)
            elif screen is not None and screen in suggestion.screens:
                specific[suggestion.toolset].append(suggestion)
        ordered = _interleave(specific) + _interleave(general)
        return ordered[:MAX_SUGGESTIONS]

    def suggestion(self, suggestion_id: str) -> SuggestionDef | None:
        return self._suggestions.get(suggestion_id)

    def all_suggestions(self) -> list[SuggestionDef]:
        """Mọi gợi ý theo thứ tự khai báo (không giới hạn 4, không lọc màn) — cho bước khớp từ khóa."""
        return list(self._suggestions.values())

    def screen_tool(self, screen: str | None, toolsets: frozenset[Toolset]) -> ScreenTool | None:
        """Tool "lấy dữ liệu màn hình" của đường B; người nhiều vai trò lấy theo thứ tự vai trò."""
        if screen is None:
            return None
        candidates = self._screen_tools.get(screen, [])
        for toolset in _TOOLSET_ORDER:
            if toolset not in toolsets:
                continue
            for screen_tool in candidates:
                if self._specs[screen_tool[0]].toolset == toolset:
                    return screen_tool
        return None


def _interleave(groups: Mapping[Toolset, list[SuggestionDef]]) -> list[SuggestionDef]:
    rows = zip_longest(*(groups[toolset] for toolset in _TOOLSET_ORDER))
    return [item for item in chain.from_iterable(rows) if item is not None]


@lru_cache(maxsize=1)
def default_registry() -> ToolRegistry:
    """Registry của tiến trình, gộp khai báo của ba vai trò."""
    registry = ToolRegistry(
        specs=(*borrower.BORROWER_TOOLS, *investor.INVESTOR_TOOLS, *admin.ADMIN_TOOLS),
        suggestions=(
            *borrower.BORROWER_SUGGESTIONS,
            *investor.INVESTOR_SUGGESTIONS,
            *admin.ADMIN_SUGGESTIONS,
        ),
    )
    for screen_tools in (
        borrower.BORROWER_SCREEN_TOOLS,
        investor.INVESTOR_SCREEN_TOOLS,
        admin.ADMIN_SCREEN_TOOLS,
    ):
        registry.add_screen_tools(screen_tools)
    return registry
