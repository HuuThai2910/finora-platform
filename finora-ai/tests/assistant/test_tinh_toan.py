"""Câu tính khoản vay gõ tự do đi thẳng vào công cụ tính, không cần LLM (sửa CHAT-004, fix-1).

Chạy thật ngày 16/10/2026: "Vay 30 triệu trong 12 tháng thì mỗi tháng trả bao nhiêu?" không có chữ "tôi" nên
bị xếp vào câu chính sách (BM25 trả đoạn NV-SAN-PHAM-01 57 điểm), gọi LLM một lần không kèm công cụ và không
bao giờ chạy `mo_phong_khoan_vay`/`goi_y_khoan_vay`; không có LLM thì người dùng chỉ nhận đoạn mô tả chung.
Số tiền và kỳ hạn đã nằm sẵn trong câu, nên code đọc được và tự gọi công cụ tính của Loan.
"""

import json

import pytest

from app.schemas.assistant import ChatRequest, ScreenContext
from app.services.assistant.knowledge.bm25 import get_index
from app.services.assistant.legal import load_legal_sources
from app.services.assistant.llm.router import LlmUnavailable
from app.services.assistant.orchestrator import AssistantOrchestrator
from app.services.assistant.principal import Toolset
from app.services.assistant.tinh_toan import YeuCauTinhVay, doc_yeu_cau_tinh_vay
from app.services.assistant.tools.registry import default_registry

from .helpers import TEST_URLS, make_principal, make_settings, route_transport
from .test_orchestrator import DongHo, KichBanLlm
from .test_tools_borrower import (
    LICH_501,
    LICH_502,
    LICH_503,
    LICH_TAM_TINH,
    TRANG_SAN_PHAM_4,
)

LOAN = TEST_URLS["loan"]
NGUOI_VAY = frozenset({Toolset.BORROWER})
NHA_DAU_TU = frozenset({Toolset.INVESTOR})
TRIEU = 1_000_000


# ── Đọc tham số từ câu ─────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("cau", "mong_doi"),
    [
        ("Vay 30 triệu trong 12 tháng thì mỗi tháng trả bao nhiêu?", YeuCauTinhVay(30 * TRIEU, 12)),
        ("vay 30tr 12 thang moi thang tra bao nhieu", YeuCauTinhVay(30 * TRIEU, 12)),
        ("Muốn vay 30.000.000 đ trong 1 năm thì góp mỗi tháng bao nhiêu?", YeuCauTinhVay(30 * TRIEU, 12)),
        ("Cần vay 1,5 tỷ trong 2 năm, mỗi tháng trả bao nhiêu?", YeuCauTinhVay(1_500 * TRIEU, 24)),
        ("Vay 40 củ 1 năm rưỡi thì trả hàng tháng bao nhiêu?", YeuCauTinhVay(40 * TRIEU, 18)),
        ("Tính thử khoản vay 20 triệu kỳ hạn 6 kỳ", YeuCauTinhVay(20 * TRIEU, 6)),
        (
            "Tôi thu nhập 15 triệu, cần vay 50 triệu trong 12 tháng, gói nào hợp nhất?",
            YeuCauTinhVay(50 * TRIEU, 12, monthly_income=15 * TRIEU, hoi_goi=True),
        ),
        (
            "Lương 20tr/tháng, đang trả góp 2 triệu mỗi tháng, muốn vay 40 triệu 18 tháng gói nào phù hợp?",
            YeuCauTinhVay(40 * TRIEU, 18, monthly_income=20 * TRIEU, monthly_debt=2 * TRIEU, hoi_goi=True),
        ),
        (
            "Vay 50 triệu trong 12 tháng, gói nào trả mỗi tháng thấp nhất?",
            YeuCauTinhVay(50 * TRIEU, 12, tieu_chi="TRA_THANG", hoi_goi=True),
        ),
        (
            "Tôi muốn vay 500 triệu trong 12 tháng, có gói nào phù hợp không?",
            YeuCauTinhVay(500 * TRIEU, 12, hoi_goi=True),
        ),
    ],
)
def test_doc_so_tien_ky_han_thu_nhap(cau, mong_doi):
    assert doc_yeu_cau_tinh_vay(cau) == mong_doi


@pytest.mark.parametrize(
    "cau",
    [
        "Vay 30 triệu thì mỗi tháng trả bao nhiêu?",  # thiếu kỳ hạn: không đoán
        "Kỳ tới tôi phải trả bao nhiêu?",
        "Sao vay tối đa chỉ 24 tháng?",  # có kỳ hạn, không có số tiền
        "Hồ sơ vay 30 triệu 12 tháng của tôi sao rồi?",  # hỏi hồ sơ đã nộp, không phải tính thử
        "Khoản vay 30 triệu 12 tháng tôi đang trả còn nợ bao nhiêu?",
        "Vì sao hồ sơ vay 30 triệu 12 tháng bị từ chối?",
        "Vay 30 triệu hay 50 triệu trong 12 tháng thì hơn?",  # hai số tiền vay: mơ hồ
        "Hạn mức 100 triệu là quy định của luật à?",
    ],
)
def test_khong_doan_khi_cau_khong_phai_tinh_thu(cau):
    assert doc_yeu_cau_tinh_vay(cau) is None


# ── Định tuyến trong orchestrator (registry, công cụ và kho kiến thức thật) ──────────────────────


def _dung(llm, transport) -> AssistantOrchestrator:
    default_registry.cache_clear()
    return AssistantOrchestrator(
        make_settings(), default_registry(), get_index(), llm, load_legal_sources(),
        transport=transport, clock=DongHo(),
    )


def _hoi(message: str, screen: str = "Home", params: dict | None = None) -> ChatRequest:
    return ChatRequest(message=message, history=[], surface="mobile",
                       context=ScreenContext(screen=screen, params=params or {}))


def _preview(lich_theo_goi: dict[int, dict]):
    """Route POST repayment-previews cho từng gói, kèm route GET danh sách gói 4 sản phẩm."""
    routes = {("GET", f"{LOAN}/api/v1/loan-products"): (200, TRANG_SAN_PHAM_4)}
    for goi, lich in lich_theo_goi.items():
        routes[("POST", f"{LOAN}/api/v1/loan-products/{goi}/repayment-previews")] = (200, lich)
    return route_transport(routes)


@pytest.mark.asyncio
async def test_chat_004_cau_tinh_thu_khong_co_goi_thi_so_sanh_goi_bang_cau_mau():
    transport = _preview({501: LICH_501, 502: LICH_502, 503: LICH_503})
    llm = KichBanLlm()  # không có bước nào: gọi LLM là test hỏng

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Vay 30 triệu trong 12 tháng thì mỗi tháng trả bao nhiêu?"),
    )

    assert phan_hoi.mode == "template"
    assert llm.calls == []
    duong_dan = [(r.method, r.url.path) for r in transport.requests]
    assert ("GET", "/api/v1/loan-products") in duong_dan
    assert ("POST", "/api/v1/loan-products/501/repayment-previews") in duong_dan
    # Số kỳ đầu/kỳ cao nhất do Loan tính (firstInstallment/maximumInstallment), không phải LLM.
    assert "2.693.621 đ" in phan_hoi.answer
    assert "Vay tiêu dùng tiêu chuẩn" in phan_hoi.answer
    assert "30.000.000 đ" in phan_hoi.answer and "12 tháng" in phan_hoi.answer
    assert phan_hoi.cards[0].title == "So sánh gói vay"
    assert "nop_ho_so" in [a.key for a in phan_hoi.actions]
    assert "CS-LAI-CO-SO" in [c.id for c in phan_hoi.citations]


@pytest.mark.asyncio
async def test_co_thu_nhap_thi_neu_ty_le_tra_no_nhung_khong_nhac_lai_thu_nhap():
    transport = _preview({501: LICH_501, 502: LICH_502, 503: LICH_503})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Tôi thu nhập 15 triệu, cần vay 30 triệu trong 12 tháng, gói nào hợp nhất?"),
    )

    assert phan_hoi.mode == "template"
    toan_bo = json.dumps(phan_hoi.model_dump(by_alias=True), ensure_ascii=False)
    assert "15.000.000" not in toan_bo and "15000000" not in toan_bo
    assert "Tỷ lệ trả nợ" in phan_hoi.cards[0].columns
    assert "%" in phan_hoi.answer  # tỷ lệ trả nợ/thu nhập của gói đứng đầu


@pytest.mark.asyncio
async def test_khong_goi_nao_khop_thi_bao_khong_co_goi():
    transport = _preview({})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Tôi muốn vay 500 triệu trong 12 tháng, có gói nào phù hợp không?"),
    )

    assert phan_hoi.mode == "template"
    assert "Không có gói vay nào" in phan_hoi.answer and "500.000.000 đ" in phan_hoi.answer
    assert all(r.method == "GET" for r in transport.requests)  # không tính lịch khi không có gói khớp


@pytest.mark.asyncio
async def test_dang_xem_mot_san_pham_thi_tinh_dung_san_pham_do():
    transport = route_transport({("POST", f"{LOAN}/api/v1/loan-products/501/repayment-previews"): (200, LICH_TAM_TINH)})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Vay 30 triệu 12 tháng thì mỗi tháng trả bao nhiêu?", screen="ProductDetail", params={"productId": "501"}),
    )

    assert phan_hoi.mode == "template"
    assert [(r.method, r.url.path) for r in transport.requests] == [
        ("POST", "/api/v1/loan-products/501/repayment-previews")
    ]
    assert json.loads(transport.requests[0].content) | {"expectedDisbursementDate": "x"} == {
        "amount": 30000000, "termMonths": 12, "expectedDisbursementDate": "x"
    }
    assert "2.693.621 đ" in phan_hoi.answer and "32.323.452 đ" in phan_hoi.answer
    assert phan_hoi.cards[0].title == "Tạm tính khoản vay"
    assert [a.key for a in phan_hoi.actions] == ["xem_san_pham"]


@pytest.mark.asyncio
async def test_nha_dau_tu_khong_co_cong_cu_tinh_khoan_vay():
    transport = route_transport({})
    llm = KichBanLlm(LlmUnavailable("het nha", [("groq_gptoss120b", "429")]))

    await _dung(llm, transport).handle(
        make_principal("ROLE_INVESTOR"), NHA_DAU_TU, _hoi("Vay 30 triệu trong 12 tháng thì mỗi tháng trả bao nhiêu?"),
    )

    assert not [r for r in transport.requests if "loan-products" in r.url.path]
