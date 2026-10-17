"""Danh bạ tool: lọc theo vai trò, allowlist route, gợi ý theo màn, tool màn hình."""

import pytest

from app.services.assistant.llm.providers import Lane
from app.services.assistant.principal import Toolset
from app.services.assistant.relay import ServiceName
from app.services.assistant.tools.base import (
    NoParams,
    RouteRule,
    SuggestionDef,
    ToolResult,
    ToolSpec,
)
from app.services.assistant.tools.registry import ToolRegistry, default_registry

B, I, A = Toolset.BORROWER, Toolset.INVESTOR, Toolset.ADMIN
VI = RouteRule("GET", ServiceName.PAYMENT, r"/api/v1/wallets/me")


async def _handler(client, params):
    return ToolResult(facts={})


def _tool(name, toolset, *routes):
    return ToolSpec(
        name=name, toolset=toolset, description=name, params_model=NoParams,
        handler=_handler, lane=Lane.USER_DATA, routes=routes,
    )


def _goi_y(suggestion_id, toolset, tool, screens=()):
    return SuggestionDef(
        id=suggestion_id, label=suggestion_id, toolset=toolset, screens=frozenset(screens),
        tool=tool, params_from_context={}, render=lambda result: "mẫu",
    )


TOOLS = (
    _tool("ho_so_cua_toi", B, RouteRule("GET", ServiceName.LOAN, r"/api/v1/loan-applications/me")),
    _tool("vi_cua_toi", B, VI),
    _tool("tien_dang_giu", I, VI, RouteRule("GET", ServiceName.INVESTMENT, r"/api/v1/investments/commitments")),
    _tool("danh_muc_cua_toi", I, RouteRule("GET", ServiceName.INVESTMENT, r"/api/v1/investments/portfolio")),
    _tool("kpi_khoan_vay", A, RouteRule("GET", ServiceName.LOAN, r"/api/v1/admin/loan-statistics/summary")),
)
SUGGESTIONS = (
    _goi_y("borrower.application.status", B, "ho_so_cua_toi", screens={"ApplicationDetail"}),
    _goi_y("borrower.application.why_rejected", B, "ho_so_cua_toi", screens={"ApplicationDetail"}),
    _goi_y("borrower.wallet.enough", B, "vi_cua_toi"),
    _goi_y("borrower.products.overview", B, "ho_so_cua_toi"),
    _goi_y("borrower.pin.locked", B, "vi_cua_toi"),
    _goi_y("investor.portfolio.summary", I, "danh_muc_cua_toi", screens={"Portfolio"}),
    _goi_y("investor.money.held", I, "tien_dang_giu"),
    _goi_y("investor.notifications.explain", I, "tien_dang_giu"),
    _goi_y("admin.kpi.overview", A, "kpi_khoan_vay"),
)


@pytest.fixture
def registry():
    return ToolRegistry(
        TOOLS,
        SUGGESTIONS,
        {"ApplicationDetail": ("ho_so_cua_toi", {}), "Portfolio": ("danh_muc_cua_toi", {})},
    )


class TestLocTheoVaiTro:
    def test_chi_tra_tool_cua_vai_tro(self, registry):
        assert [spec.name for spec in registry.for_toolsets(frozenset({B}))] == ["ho_so_cua_toi", "vi_cua_toi"]
        assert [spec.name for spec in registry.for_toolsets(frozenset({B, I}))] == [
            "ho_so_cua_toi", "vi_cua_toi", "tien_dang_giu", "danh_muc_cua_toi",
        ]
        assert registry.for_toolsets(frozenset()) == []

    def test_get_theo_ten(self, registry):
        assert registry.get("kpi_khoan_vay").toolset is A
        assert registry.get("khong_co") is None

    def test_routes_cua_vai_tro_bo_trung(self, registry):
        routes = registry.routes_for(frozenset({B, I}))
        assert routes.count(VI) == 1
        assert all(rule.service is not ServiceName.LOAN or "admin" not in rule.pattern for rule in routes)
        assert len(registry.routes_for(frozenset({A}))) == 1


class TestGoiY:
    def test_goi_y_rieng_cua_man_dung_truoc_toi_da_4(self, registry):
        ids = [s.id for s in registry.suggestions_for(frozenset({B}), "ApplicationDetail")]
        assert ids == [
            "borrower.application.status",
            "borrower.application.why_rejected",
            "borrower.wallet.enough",
            "borrower.products.overview",
        ]

    def test_khong_co_man_chi_lay_goi_y_chung(self, registry):
        ids = [s.id for s in registry.suggestions_for(frozenset({B}), None)]
        assert ids == ["borrower.wallet.enough", "borrower.products.overview", "borrower.pin.locked"]

    def test_hai_vai_tro_xen_ke(self, registry):
        ids = [s.id for s in registry.suggestions_for(frozenset({B, I}), "Home")]
        assert ids == [
            "borrower.wallet.enough",
            "investor.money.held",
            "borrower.products.overview",
            "investor.notifications.explain",
        ]

    def test_khong_lo_goi_y_vai_tro_khac(self, registry):
        ids = [s.id for s in registry.suggestions_for(frozenset({A}), "Portfolio")]
        assert ids == ["admin.kpi.overview"]

    def test_tim_goi_y_theo_id(self, registry):
        assert registry.suggestion("investor.money.held").tool == "tien_dang_giu"
        assert registry.suggestion("khong.co") is None


class TestToolManHinh:
    def test_theo_man_va_vai_tro(self, registry):
        assert registry.screen_tool("ApplicationDetail", frozenset({B})) == ("ho_so_cua_toi", {})
        assert registry.screen_tool("ApplicationDetail", frozenset({I})) is None
        assert registry.screen_tool("Portfolio", frozenset({B, I})) == ("danh_muc_cua_toi", {})
        assert registry.screen_tool("MyBookOrders", frozenset({I})) is None
        assert registry.screen_tool(None, frozenset({B})) is None

    def test_man_chung_cua_hai_vai_tro(self, registry):
        registry.add_screen_tools({"Home": ("vi_cua_toi", {})})
        registry.add_screen_tools({"Home": ("tien_dang_giu", {})})
        assert registry.screen_tool("Home", frozenset({I})) == ("tien_dang_giu", {})
        assert registry.screen_tool("Home", frozenset({B, I})) == ("vi_cua_toi", {})

    def test_mot_vai_tro_hai_tool_cung_man_la_loi(self, registry):
        with pytest.raises(ValueError):
            registry.add_screen_tools({"ApplicationDetail": ("vi_cua_toi", {})})


class TestKhaiBaoSai:
    def test_trung_ten_tool(self):
        with pytest.raises(ValueError, match="Trùng tên tool"):
            ToolRegistry([TOOLS[0], TOOLS[0]])

    def test_goi_y_tro_tool_khong_co(self):
        with pytest.raises(ValueError, match="chưa khai báo"):
            ToolRegistry(TOOLS, [_goi_y("x.y", B, "khong_co")])

    def test_goi_y_khac_vai_tro_voi_tool(self):
        with pytest.raises(ValueError, match="khác vai trò"):
            ToolRegistry(TOOLS, [_goi_y("x.y", I, "ho_so_cua_toi")])

    def test_trung_id_goi_y(self):
        with pytest.raises(ValueError, match="Trùng id"):
            ToolRegistry(TOOLS, [SUGGESTIONS[0], SUGGESTIONS[0]])

    def test_man_tro_tool_khong_co(self):
        with pytest.raises(ValueError):
            ToolRegistry(TOOLS, (), {"Home": ("khong_co", {})})


class TestDefaultRegistry:
    def test_dung_duoc_va_co_cache(self):
        default_registry.cache_clear()
        registry = default_registry()
        assert registry is default_registry()
        assert isinstance(registry, ToolRegistry)
