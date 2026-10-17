"""Câu hỏi pháp lý dùng đoạn pháp lý của kho kiến thức và căn cứ do code gắn (sửa CHAT-014, fix-1).

Chạy thật ngày 16/10/2026: "Sao vay tối đa chỉ 24 tháng?" khớp cụm "vay tối đa" của gợi ý sản phẩm (đường C1,
chạy trước câu chính sách) nên nhận bảng sản phẩm; đoạn PL-THOI-HAN-01 (75 điểm BM25) không được dùng, không có
căn cứ LUAT, không có câu miễn trừ pháp lý. Dùng kho kiến thức và nguồn pháp lý thật để chốt đúng nội dung.
"""

import pytest

from app.schemas.assistant import ChatRequest, ScreenContext
from app.services.assistant.knowledge.bm25 import get_index
from app.services.assistant.legal import load_legal_sources
from app.services.assistant.llm.providers import Lane
from app.services.assistant.llm.router import LlmUnavailable
from app.services.assistant.orchestrator import (
    MIEN_TRU_PHAP_LY,
    AssistantOrchestrator,
    hoi_phap_ly,
)
from app.services.assistant.principal import Toolset
from app.services.assistant.tools.registry import default_registry

from .helpers import make_principal, make_settings, route_transport
from .test_orchestrator import DongHo, KichBanLlm, tra_loi

NGUOI_VAY = frozenset({Toolset.BORROWER})
QUAN_TRI = frozenset({Toolset.ADMIN})
KHONG_CO_LLM = LlmUnavailable("het nha", [("gemini_gemma31b", "HTTP_402")])


def _dung(llm, transport) -> AssistantOrchestrator:
    default_registry.cache_clear()
    return AssistantOrchestrator(
        make_settings(), default_registry(), get_index(), llm, load_legal_sources(),
        transport=transport, clock=DongHo(),
    )


def _hoi(message: str, screen: str = "Home", surface: str = "mobile") -> ChatRequest:
    return ChatRequest(message=message, history=[], surface=surface, context=ScreenContext(screen=screen))


@pytest.mark.parametrize(
    "cau",
    [
        "Theo luật thì được vay tối đa bao lâu?",
        "Theo Điều 15 Nghị định 94/2025 thì được vay tối đa 36 tháng đúng không?",
        "Hạn mức 100 triệu là quy định của luật à?",
        "Phí tất toán có hợp pháp không?",
        "Quy định pháp luật về lãi suất là gì?",
        "Thông tư nào quy định lãi quá hạn?",
        "Lãi suất này điều mấy cho phép?",
    ],
)
def test_nhan_ra_cau_hoi_phap_ly(cau):
    assert hoi_phap_ly(cau)


@pytest.mark.parametrize(
    "cau",
    [
        "Sao vay tối đa chỉ 24 tháng?",  # không có chữ pháp lý: đi theo điểm đoạn kiến thức
        "Luật chấm điểm R1 trừ bao nhiêu điểm?",  # luật chấm điểm nội bộ của FINORA
        "Bộ luật chấm điểm hiện có mấy luật?",
        "Kỳ tới tôi phải trả bao nhiêu?",
    ],
)
def test_cau_khong_phai_cau_hoi_phap_ly(cau):
    assert not hoi_phap_ly(cau)


@pytest.mark.asyncio
async def test_chat_014_vay_toi_da_24_thang_dung_doan_phap_ly_va_can_cu_luat():
    transport = route_transport({})

    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Sao vay tối đa chỉ 24 tháng?"),
    )

    assert transport.requests == []  # không lấy bảng sản phẩm
    assert phan_hoi.mode == "fallback"
    assert "02 năm" in phan_hoi.answer and "24 tháng" in phan_hoi.answer
    luat = [c for c in phan_hoi.citations if c.kind == "LUAT"]
    assert luat and luat[0].id.startswith("LAW-P2P-2025")
    assert "Điều 11" in luat[0].label and luat[0].url
    assert phan_hoi.disclaimer is not None and MIEN_TRU_PHAP_LY in phan_hoi.disclaimer


@pytest.mark.asyncio
async def test_chat_014_co_llm_thi_goi_lan_public_mot_lan_kem_doan_phap_ly():
    llm = KichBanLlm(tra_loi("Hợp đồng vay ngang hàng thử nghiệm không được dài quá 02 năm, nên tối đa 24 tháng."))

    phan_hoi = await _dung(llm, route_transport({})).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Sao vay tối đa chỉ 24 tháng?"),
    )

    assert phan_hoi.mode == "llm"
    assert len(llm.calls) == 1 and llm.calls[0]["lane"] == Lane.PUBLIC and llm.calls[0]["tools"] is None
    assert "PL-THOI-HAN-01" in llm.calls[0]["messages"][-1]["content"]
    assert "LUAT" in {c.kind for c in phan_hoi.citations}


@pytest.mark.asyncio
async def test_bay_so_dieu_tra_loi_bang_doan_ky_han_khong_lap_lai_dieu_sai():
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), route_transport({})).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Theo Điều 15 Nghị định 94/2025 thì được vay tối đa 36 tháng đúng không?"),
    )

    assert "24 tháng" in phan_hoi.answer and "Điều 15" not in phan_hoi.answer
    assert "LUAT" in {c.kind for c in phan_hoi.citations}


@pytest.mark.asyncio
async def test_cau_hoi_phap_ly_uu_tien_doan_luat_hon_doan_chinh_sach():
    # BM25 thô: NV-TAT-TOAN-01 (chính sách) 41,5 điểm, cao hơn hai đoạn LUAT (38,6 và 37,7).
    llm = KichBanLlm(KHONG_CO_LLM)

    phan_hoi = await _dung(llm, route_transport({})).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Thu phí tất toán như vậy có đúng luật không?"),
    )

    gui_di = llm.calls[0]["messages"][-1]["content"]
    assert gui_di.index('"kind":"LUAT"') < gui_di.index('"kind":"CHINH_SACH"')
    assert phan_hoi.citations[0].kind == "LUAT"


@pytest.mark.asyncio
async def test_han_muc_chua_xac_minh_gan_nhan_chua_xac_minh():
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), route_transport({})).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Hạn mức 100 triệu là quy định của luật à?"),
    )

    assert "100 triệu" in phan_hoi.answer
    assert phan_hoi.citations[0].kind == "CHUA_XAC_MINH"


@pytest.mark.asyncio
async def test_quan_tri_hoi_400_trieu_da_xac_minh_chua_khong_bi_cau_kpi_chiem():
    """QT-CS-04 của bộ đánh giá: cụm "dư nợ" khớp gợi ý KPI (C1) nên trước đây nhận câu KPI danh mục."""
    transport = route_transport({})

    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI,
        _hoi("Mức 400 triệu tổng dư nợ trên mọi nền tảng đã là quy định được xác minh chưa?", "Dashboard", "web"),
    )

    assert transport.requests == []
    assert "400 triệu" in phan_hoi.answer
    assert "CHUA_XAC_MINH" in {c.kind for c in phan_hoi.citations}


@pytest.mark.asyncio
async def test_chinh_sach_noi_bo_van_tra_bang_cau_mau_nguong():
    """CHAT-013 không đổi: đoạn đứng đầu là chính sách nội bộ, không phải đoạn pháp lý."""
    llm = KichBanLlm()

    phan_hoi = await _dung(llm, route_transport({})).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI, _hoi("Vì sao hồ sơ 70 điểm vẫn phải duyệt tay?", "Dashboard", "web"),
    )

    assert phan_hoi.mode == "template" and llm.calls == []
    assert {c.kind for c in phan_hoi.citations} == {"CHINH_SACH"}
