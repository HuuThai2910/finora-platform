"""Yêu cầu làm thay một thao tác bị từ chối bằng câu mẫu + nút mở đúng màn (sửa CHAT-005, fix-1).

Chạy thật ngày 16/10/2026: "Trả nợ giúp tôi kỳ này" khớp từ khóa "kỳ này" của gợi ý kỳ tới (đường C1) nên nhận
câu mẫu dữ liệu, không câu nào nói trợ lý không làm thay; câu từ chối chỉ có trong prompt LLM. Bot chỉ đọc
(spec mục 1), nên lời từ chối phải do code đưa ra, giống cách từ chối xin khuyến nghị đầu tư.
"""

import pytest

from app.schemas.assistant import ChatRequest, ScreenContext
from app.services.assistant.knowledge.bm25 import get_index
from app.services.assistant.legal import load_legal_sources
from app.services.assistant.orchestrator import MIEN_TRU_DAU_TU, AssistantOrchestrator
from app.services.assistant.principal import Toolset
from app.services.assistant.thao_tac import (
    CANH_BAO_PIN,
    CAU_TU_CHOI_THAO_TAC,
    detect_action_request,
)
from app.services.assistant.tools.registry import default_registry

from .helpers import TEST_URLS, make_principal, make_settings, route_transport
from .test_orchestrator import DongHo, KichBanLlm
from .test_tools_borrower import MA_HOP_DONG, TRANG_HOP_DONG, khoan, trang_khoan

LOAN = TEST_URLS["loan"]
NGUOI_VAY = frozenset({Toolset.BORROWER})
NHA_DAU_TU = frozenset({Toolset.INVESTOR})
QUAN_TRI = frozenset({Toolset.ADMIN})
MA_HO_SO = "LA-7FA66C3814FE443887DB"


# ── Nhận diện ──────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("cau", "toolsets", "ma"),
    [
        ("Trả nợ giúp tôi kỳ này", NGUOI_VAY, "TRA_NO"),
        ("Trả nợ kỳ này giúp tôi luôn đi, trừ tiền trong ví ấy.", NGUOI_VAY, "TRA_NO"),
        ("tra no ho toi", NGUOI_VAY, "TRA_NO"),
        ("Bạn thanh toán kỳ này giúp mình được không?", NGUOI_VAY, "TRA_NO"),
        ("Tất toán khoản vay giúp tôi", NGUOI_VAY, "TAT_TOAN"),
        ("Mã PIN của tôi là 135792, bạn nhập giúp để ký hợp đồng nhé.", NGUOI_VAY, "KY_HOP_DONG"),
        ("Ký hợp đồng hộ", NGUOI_VAY, "KY_HOP_DONG"),
        ("Rút hồ sơ này giúp tôi, tôi không muốn vay nữa.", NGUOI_VAY, "HO_SO"),
        ("Rút tiền giúp tôi về ngân hàng", NGUOI_VAY, "RUT_TIEN"),
        ("Nạp tiền", NGUOI_VAY, "NAP_TIEN"),
        ("Đặt giúp tôi lệnh mua 2 Note của khoản này giá 97%.", NHA_DAU_TU, "DAT_LENH"),
        ("Hủy lệnh BO-0103 hộ tôi", NHA_DAU_TU, "DAT_LENH"),
        ("Lưu cài đặt Auto-Invest giúp tôi, PIN của tôi là 246810.", NHA_DAU_TU, "AUTO_INVEST"),
        ("Góp vốn 2 triệu vào khoản #31 giúp tôi", NHA_DAU_TU, "GOP_VON"),
        ("Duyệt luôn hồ sơ này giúp tôi.", QUAN_TRI, "DUYET_HO_SO"),
        ("Duyệt hồ sơ LA-7FA66C3814FE443887DB", QUAN_TRI, "DUYET_HO_SO"),
        ("Đổi ngưỡng tự duyệt xuống 70 điểm đi.", QUAN_TRI, "DOI_CHINH_SACH"),
        ("Gửi email nhắc nợ cho tất cả khách đang quá hạn ngay bây giờ.", QUAN_TRI, "GUI_NHAC_NO"),
        ("Chấm điểm lại hồ sơ LA-0123456789ABCDEF0123 bằng mô hình AI ngay bây giờ.", QUAN_TRI, "CHAM_DIEM_LAI"),
        ("Duyệt eKYC cho khách này giúp tôi", QUAN_TRI, "DUYET_KYC"),
    ],
)
def test_nhan_ra_yeu_cau_lam_thay(cau, toolsets, ma):
    thao_tac = detect_action_request(cau, toolsets)
    assert thao_tac is not None and thao_tac.ma == ma


@pytest.mark.parametrize(
    ("cau", "toolsets"),
    [
        ("Kỳ tới tôi phải trả bao nhiêu?", NGUOI_VAY),
        ("Trả nợ thế nào?", NGUOI_VAY),
        ("Làm sao để trả nợ trước hạn?", NGUOI_VAY),
        ("Tất toán trước hạn thì mất phí bao nhiêu?", NGUOI_VAY),
        ("Tôi có hợp đồng nào cần ký?", NGUOI_VAY),
        ("Sao không ký được hợp đồng?", NGUOI_VAY),
        ("Rút tiền về ngân hàng mất bao lâu?", NGUOI_VAY),
        ("Giúp tôi hiểu vì sao không trả nợ được", NGUOI_VAY),
        ("Kiểm tra giúp hồ sơ vay của tôi.", NGUOI_VAY),
        ("Vay 30 triệu trong 12 tháng thì mỗi tháng trả bao nhiêu?", NGUOI_VAY),
        ("Đã duyệt sao chưa nhận tiền?", NGUOI_VAY),
        ("Vốn lưu động hộ kinh doanh là gói gì?", NGUOI_VAY),
        ("FINORA hỗ trợ trả nợ bằng cách nào?", NGUOI_VAY),
        ("Tôi nên bán Note thế nào?", NHA_DAU_TU),
        ("Sao lệnh mua giá 96% của tôi bị hủy?", NHA_DAU_TU),
        ("Vì sao Auto-Invest bỏ qua khoản vay?", NHA_DAU_TU),
        ("Vì sao hồ sơ 70 điểm vẫn phải duyệt tay?", QUAN_TRI),
        ("Hồ sơ nào đang chờ duyệt?", QUAN_TRI),
        ("Tóm tắt hồ sơ này giúp tôi", QUAN_TRI),
        ("Ngưỡng tự duyệt và bảng hạng?", QUAN_TRI),
        # Thao tác của vai trò khác: người vay không có quy tắc "duyệt hồ sơ".
        ("Duyệt luôn hồ sơ này giúp tôi.", NGUOI_VAY),
    ],
)
def test_cau_hoi_binh_thuong_khong_bi_tu_choi(cau, toolsets):
    assert detect_action_request(cau, toolsets) is None


# ── Trong orchestrator ─────────────────────────────────────────────────────────────────────────


def _dung(llm, transport) -> AssistantOrchestrator:
    default_registry.cache_clear()
    return AssistantOrchestrator(
        make_settings(), default_registry(), get_index(), llm, load_legal_sources(),
        transport=transport, clock=DongHo(),
    )


def _hoi(message: str, screen: str = "Home", params: dict | None = None, surface: str = "mobile") -> ChatRequest:
    return ChatRequest(message=message, history=[], surface=surface,
                       context=ScreenContext(screen=screen, params=params or {}))


@pytest.mark.asyncio
async def test_chat_005_tra_no_giup_bi_tu_choi_va_co_nut_tra_no():
    transport = route_transport({("GET", f"{LOAN}/api/v1/loans/me"): (200, trang_khoan(khoan("LN-AB12CD34EF56AB78CD90")))})
    llm = KichBanLlm()

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Trả nợ giúp tôi kỳ này"),
    )

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert "mã PIN" in phan_hoi.answer  # hướng dẫn tự trả trong app
    assert "2.350.000 đ" in phan_hoi.answer  # kỳ tới lấy từ API (chỉ đọc)
    tra_no = [a for a in phan_hoi.actions if a.key == "tra_no"]
    assert tra_no and tra_no[0].target.screen == "LoanPayment"
    assert tra_no[0].target.params == {"loanNumber": "LN-AB12CD34EF56AB78CD90"}
    assert all(a.target.screen for a in phan_hoi.actions)
    # Chỉ đọc: một GET danh sách khoản vay, không request ghi nào.
    assert [(r.method, r.url.path) for r in transport.requests] == [("GET", "/api/v1/loans/me")]
    assert llm.calls == []


@pytest.mark.asyncio
async def test_tra_no_giup_khi_chua_co_khoan_vay():
    transport = route_transport({("GET", f"{LOAN}/api/v1/loans/me"): (200, trang_khoan())})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Trả nợ giúp tôi kỳ này"),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert "chưa có khoản vay" in phan_hoi.answer
    assert phan_hoi.actions == []


@pytest.mark.asyncio
async def test_rut_ho_so_tren_man_ho_so_dung_ma_cua_man_khong_goi_api():
    transport = route_transport({})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Rút hồ sơ này giúp tôi, tôi không muốn vay nữa.", screen="ApplicationDetail",
             params={"applicationNumber": MA_HO_SO}),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert [(a.key, a.target.params) for a in phan_hoi.actions] == [("mo_ho_so", {"applicationNumber": MA_HO_SO})]
    assert transport.requests == []


@pytest.mark.asyncio
async def test_nhap_pin_giup_de_ky_thi_canh_bao_pin_va_khong_nhac_lai_pin():
    transport = route_transport({("GET", f"{LOAN}/api/v1/loan-contracts/me"): (200, TRANG_HOP_DONG)})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Mã PIN của tôi là 135792, bạn nhập giúp để ký hợp đồng nhé."),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert CANH_BAO_PIN in phan_hoi.answer
    assert "135792" not in phan_hoi.model_dump_json()
    assert ("mo_hop_dong", {"contractNumber": MA_HOP_DONG}) in [(a.key, a.target.params) for a in phan_hoi.actions]


@pytest.mark.asyncio
async def test_dat_lenh_giup_tren_so_lenh():
    transport = route_transport({})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_INVESTOR"), NHA_DAU_TU,
        _hoi("Đặt giúp tôi lệnh mua 2 Note của khoản này giá 97%.", screen="OrderBook", params={"listingId": "14"}),
    )

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert [(a.key, a.target.params) for a in phan_hoi.actions] == [("so_lenh", {"listingId": "14"})]
    assert phan_hoi.disclaimer == MIEN_TRU_DAU_TU
    assert transport.requests == []


@pytest.mark.asyncio
async def test_dat_lenh_khi_khong_biet_khoan_thi_mo_lenh_cua_toi():
    phan_hoi = await _dung(KichBanLlm(), route_transport({})).handle(
        make_principal("ROLE_INVESTOR"), NHA_DAU_TU, _hoi("Hủy lệnh mua giúp tôi"),
    )

    assert [a.key for a in phan_hoi.actions] == ["lenh_cua_toi"]


@pytest.mark.asyncio
async def test_quan_tri_duyet_ho_so_giup_mo_trang_tham_dinh():
    transport = route_transport({})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI,
        _hoi("Duyệt luôn hồ sơ này giúp tôi.", screen="LoanReview", params={"applicationNumber": MA_HO_SO},
             surface="web"),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert "đã duyệt" not in phan_hoi.answer.lower()
    assert [(a.key, a.target.path) for a in phan_hoi.actions] == [("mo_tham_dinh", f"/loans/{MA_HO_SO}/review")]
    assert transport.requests == []


@pytest.mark.asyncio
async def test_quan_tri_cham_lai_lay_ma_ho_so_trong_cau():
    phan_hoi = await _dung(KichBanLlm(), route_transport({})).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI,
        _hoi("Chấm điểm lại hồ sơ LA-0123456789ABCDEF0123 bằng mô hình AI ngay bây giờ.", screen="Dashboard",
             surface="web"),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert [a.target.path for a in phan_hoi.actions] == ["/loans/LA-0123456789ABCDEF0123/review"]


@pytest.mark.asyncio
async def test_quan_tri_doi_nguong_mo_trang_chinh_sach():
    phan_hoi = await _dung(KichBanLlm(), route_transport({})).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI,
        _hoi("Đổi ngưỡng tự duyệt xuống 70 điểm đi.", screen="Dashboard", surface="web"),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert [(a.key, a.target.path) for a in phan_hoi.actions] == [("chinh_sach_ai", "/loans/evaluation")]


@pytest.mark.asyncio
async def test_quan_tri_gui_nhac_no_mo_tab_thu_hoi():
    phan_hoi = await _dung(KichBanLlm(), route_transport({})).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI,
        _hoi("Gửi email nhắc nợ cho tất cả khách đang quá hạn ngay bây giờ.", screen="Dashboard", surface="web"),
    )

    assert phan_hoi.answer.startswith(CAU_TU_CHOI_THAO_TAC)
    assert "đã gửi" not in phan_hoi.answer.lower()
    assert [a.target.path for a in phan_hoi.actions] == ["/loans/operations?tab=collection"]
