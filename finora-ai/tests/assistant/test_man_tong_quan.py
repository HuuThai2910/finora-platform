"""Màn tổng quan (Trang chủ người vay, Dashboard quản trị) không chiếm đường B (lỗi tìm thấy ở Task 21).

Chạy thật trên cụm docker: app mobile mở trợ lý từ nút nổi Trang chủ nên MỌI lượt gửi `screen=Home`;
web mở ngăn chat ở `/dashboard` nên gửi `screen=Dashboard`. Khi hai màn này có công cụ "lấy dữ liệu màn
hình", đường B chạy trước C1/C2/C3: câu tự do nào của người vay cũng chỉ có danh sách hồ sơ và một lần
gọi LLM không kèm công cụ, câu nào của quản trị cũng chỉ có KPI. Hệ quả đo được: "Vì sao hồ sơ 70 điểm vẫn
phải duyệt tay?" nhận câu KPI danh mục, "Kỳ tới trả bao nhiêu?" nhận câu trạng thái hồ sơ, và có LLM
thì "Vay 30 triệu 12 tháng..." cũng không gọi được công cụ mô phỏng. Hai màn này không gắn với một đối
tượng cụ thể nên không có "câu gắn màn hình"; nhà đầu tư vốn không có công cụ cho Trang chủ.
"""

import pytest

from app.schemas.assistant import ChatRequest, ScreenContext
from app.services.assistant.knowledge.bm25 import get_index
from app.services.assistant.legal import load_legal_sources
from app.services.assistant.orchestrator import AssistantOrchestrator
from app.services.assistant.principal import Toolset
from app.services.assistant.tools.registry import default_registry

from .helpers import TEST_URLS, make_principal, make_settings, route_transport
from .test_orchestrator import DongHo, KichBanLlm

NGUOI_VAY = frozenset({Toolset.BORROWER})
QUAN_TRI = frozenset({Toolset.ADMIN})
TRANG_RONG = {"data": [], "page": 0, "size": 10, "totalElements": 0}


def _dung(llm, transport) -> AssistantOrchestrator:
    default_registry.cache_clear()
    return AssistantOrchestrator(
        make_settings(), default_registry(), get_index(), llm, load_legal_sources(),
        transport=transport, clock=DongHo(),
    )


def _hoi(message: str, screen: str, surface: str = "mobile") -> ChatRequest:
    return ChatRequest(message=message, history=[], surface=surface, context=ScreenContext(screen=screen))


def _duong_dan(transport) -> list[str]:
    return [request.url.path for request in transport.requests]


def test_trang_chu_va_dashboard_khong_co_cong_cu_man_hinh():
    default_registry.cache_clear()
    registry = default_registry()

    assert registry.screen_tool("Home", NGUOI_VAY) is None
    assert registry.screen_tool("Dashboard", QUAN_TRI) is None
    # Màn gắn với một đối tượng/danh sách vẫn đi đường B như thiết kế.
    assert registry.screen_tool("ApplicationDetail", NGUOI_VAY)[0] == "chi_tiet_ho_so"
    assert registry.screen_tool("LoanReview", QUAN_TRI)[0] == "tom_tat_ho_so"
    assert registry.screen_tool("Operations", QUAN_TRI)[0] == "viec_can_lam"


@pytest.mark.asyncio
async def test_cau_tu_do_o_trang_chu_khop_goi_y_ky_toi_chu_khong_tra_trang_thai_ho_so():
    transport = route_transport({("GET", f"{TEST_URLS['loan']}/api/v1/loans/me"): (200, TRANG_RONG)})
    llm = KichBanLlm()  # không có bước nào: gọi LLM là test hỏng

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Kỳ tới tôi phải trả bao nhiêu?", "Home"),
    )

    assert phan_hoi.mode == "template"
    assert _duong_dan(transport) == ["/api/v1/loans/me"]
    assert llm.calls == []


@pytest.mark.asyncio
async def test_cau_chinh_sach_o_dashboard_tra_nguong_duyet_khong_tra_kpi():
    transport = route_transport({})
    llm = KichBanLlm()

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI,
        _hoi("Vì sao hồ sơ 70 điểm vẫn phải duyệt tay?", "Dashboard", surface="web"),
    )

    assert phan_hoi.mode == "template"
    assert "75" in phan_hoi.answer and "65" in phan_hoi.answer
    # Không gọi API KPI của Loan: chính sách đọc từ cấu hình trong tiến trình.
    assert _duong_dan(transport) == []
    assert {c.kind for c in phan_hoi.citations} == {"CHINH_SACH"}
    assert llm.calls == []


@pytest.mark.asyncio
async def test_cau_can_cong_cu_o_trang_chu_duoc_gui_llm_kem_cong_cu():
    """Câu không khớp gợi ý và không phải câu chính sách thuần: đường C3, LLM thấy công cụ của vai trò."""
    from app.services.assistant.llm.router import LlmUnavailable

    transport = route_transport({})
    llm = KichBanLlm(LlmUnavailable("het nha", [("groq_gptoss120b", "429")]))

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Tôi muốn biết tổng tiền tôi phải trả cho FINORA", "Home"),
    )

    assert phan_hoi.mode == "fallback"
    assert len(llm.calls) == 1
    ten_cong_cu = {schema["function"]["name"] for schema in llm.calls[0]["tools"] or []}
    assert {"mo_phong_khoan_vay", "khoan_vay_cua_toi", "vi_cua_toi"} <= ten_cong_cu
    # Không có dữ liệu "màn Trang chủ" nào được lấy sẵn trước khi LLM chọn công cụ.
    assert _duong_dan(transport) == []
