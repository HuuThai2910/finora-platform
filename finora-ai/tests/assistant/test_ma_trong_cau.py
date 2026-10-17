"""Gợi ý cần mã nghiệp vụ lấy mã người dùng gõ trong câu khi màn đang mở không có (fix-1, mục 6).

Hải hỏi: quản trị viên mở trợ lý ở tab nào cũng hỏi được "tóm tắt hồ sơ LA-…" không? Trước bản sửa, gợi ý
`admin.review.summary` chỉ chạy được khi `context.params` có `applicationNumber` (màn LoanReview); ở Dashboard
câu đó rơi xuống câu chính sách/LLM và không có LLM thì nhận một đoạn kiến thức lạc đề. Mã người dùng tự gõ vốn
đã được coi là "đã thấy" (orchestrator `_ma_trong_cau`), nên dùng thẳng khi câu có đúng một mã đúng loại. Thiếu
mã hoặc có nhiều mã thì hỏi lại bằng câu mẫu thay vì trả lời lạc đề.
"""

import pytest

from app.schemas.assistant import ChatRequest, ScreenContext
from app.services.assistant.knowledge.bm25 import get_index
from app.services.assistant.legal import load_legal_sources
from app.services.assistant.orchestrator import (
    AssistantOrchestrator,
    ma_trong_cau_theo_loai,
)
from app.services.assistant.principal import Toolset
from app.services.assistant.tools.registry import default_registry

from .helpers import TEST_URLS, make_principal, make_settings, route_transport
from .test_orchestrator import DongHo, KichBanLlm
from .test_tools_admin import (
    EXPLANATION,
    MA_HO_SO,
    REVIEW_DETAIL,
    URL_EXPLANATION,
    URL_REVIEW,
)
from .test_tools_borrower import TRANG_HO_SO

LOAN = TEST_URLS["loan"]
NGUOI_VAY = frozenset({Toolset.BORROWER})
QUAN_TRI = frozenset({Toolset.ADMIN})


@pytest.mark.parametrize(
    ("cau", "ten", "mong_doi"),
    [
        ("Tóm tắt hồ sơ LA-7FA66C3814FE443887DB giúp tôi", "applicationNumber", {"LA-7FA66C3814FE443887DB"}),
        ("tóm tắt hồ sơ la-7fa66c3814fe443887db", "applicationNumber", {"LA-7FA66C3814FE443887DB"}),
        ("So hồ sơ LA-AAA1 với LA-BBB2", "applicationNumber", {"LA-AAA1", "LA-BBB2"}),
        ("Hợp đồng LC-3512BFF2CC0B4DB8AEA3 cần ký chưa?", "contractNumber", {"LC-3512BFF2CC0B4DB8AEA3"}),
        ("Hợp đồng LC-3512BFF2CC0B4DB8AEA3 cần ký chưa?", "applicationNumber", set()),
        ("Giải thích khoản #31 giúp tôi", "loanId", {"31"}),
        ("Giải thích khoản số 31", "listingId", {"31"}),
        ("Khoản vay 30 triệu này thế nào?", "loanId", set()),  # số tiền, không phải số khoản
    ],
)
def test_ma_trong_cau_theo_loai(cau, ten, mong_doi):
    assert ma_trong_cau_theo_loai(cau, ten) == mong_doi


def _dung(llm, transport) -> AssistantOrchestrator:
    default_registry.cache_clear()
    return AssistantOrchestrator(
        make_settings(), default_registry(), get_index(), llm, load_legal_sources(),
        transport=transport, clock=DongHo(),
    )


def _hoi(message: str, screen: str = "Dashboard", surface: str = "web") -> ChatRequest:
    return ChatRequest(message=message, history=[], surface=surface, context=ScreenContext(screen=screen))


@pytest.mark.asyncio
async def test_quan_tri_o_dashboard_tom_tat_ho_so_theo_ma_trong_cau():
    transport = route_transport({("GET", URL_REVIEW): (200, REVIEW_DETAIL), ("GET", URL_EXPLANATION): (200, EXPLANATION)})
    llm = KichBanLlm()

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI, _hoi(f"Tóm tắt hồ sơ {MA_HO_SO}"),
    )

    assert phan_hoi.mode == "template"
    assert llm.calls == []
    assert sorted(r.url.path for r in transport.requests) == sorted([
        f"/api/v1/admin/loan-applications/{MA_HO_SO}/review",
        f"/api/v1/admin/loan-applications/{MA_HO_SO}/explanation",
    ])
    assert "70,5" in phan_hoi.answer
    assert f"/loans/{MA_HO_SO}/review" in [a.target.path for a in phan_hoi.actions]


@pytest.mark.asyncio
async def test_quan_tri_thieu_ma_thi_hoi_lai_kem_nut_hang_doi():
    transport = route_transport({})
    llm = KichBanLlm()

    phan_hoi = await _dung(llm, transport).handle(make_principal("ROLE_ADMIN"), QUAN_TRI, _hoi("Tóm tắt hồ sơ giúp tôi"))

    assert phan_hoi.mode == "template"
    assert "hồ sơ nào" in phan_hoi.answer and "LA-" in phan_hoi.answer
    assert [a.key for a in phan_hoi.actions] == ["hang_doi_tham_dinh"]
    assert transport.requests == [] and llm.calls == []


@pytest.mark.asyncio
async def test_quan_tri_nhieu_ma_thi_hoi_lai():
    transport = route_transport({})

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI, _hoi("Tóm tắt hồ sơ LA-AAAA1111 và LA-BBBB2222"),
    )

    assert "hồ sơ nào" in phan_hoi.answer
    assert transport.requests == []


@pytest.mark.asyncio
async def test_nguoi_vay_o_trang_chu_hoi_ho_so_cu_the_theo_ma_trong_cau():
    ma = "LA-7FA66C3814FE443887DB"
    from .test_tools_borrower import LICH_SU_DUYET, ho_so

    transport = route_transport({
        ("GET", f"{LOAN}/api/v1/loan-applications/{ma}"): (200, ho_so(status="REJECTED", funding=None)),
        ("GET", f"{LOAN}/api/v1/loan-applications/{ma}/history"): (200, LICH_SU_DUYET),
    })

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi(f"Vì sao hồ sơ {ma} bị từ chối?", "Home", "mobile"),
    )

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer.startswith(f"Hồ sơ {ma} không được duyệt")


@pytest.mark.asyncio
async def test_nguoi_vay_nhieu_ho_so_thi_hoi_lai_kem_danh_sach():
    trang_rong = {"data": [], "page": 0, "size": 20, "totalElements": 0}
    transport = route_transport({
        ("GET", f"{LOAN}/api/v1/loan-applications/me"): (200, TRANG_HO_SO),
        # fix-2: danh sách có hồ sơ đã duyệt thì tra hợp đồng/khoản vay để biết giai đoạn (khớp app).
        ("GET", f"{LOAN}/api/v1/loan-contracts/me"): (200, trang_rong),
        ("GET", f"{LOAN}/api/v1/loans/me"): (200, trang_rong),
    })

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Vì sao hồ sơ của tôi bị từ chối?", "Home", "mobile"),
    )

    assert phan_hoi.mode == "template"
    assert "hồ sơ nào" in phan_hoi.answer
    assert phan_hoi.cards and phan_hoi.cards[0].title == "Hồ sơ vay của tôi"
    assert [a.key for a in phan_hoi.actions] == ["mo_ho_so"]
    assert [r.url.path for r in transport.requests] == [
        "/api/v1/loan-applications/me", "/api/v1/loan-contracts/me", "/api/v1/loans/me"]


@pytest.mark.asyncio
async def test_nguoi_vay_mot_ho_so_thi_tra_loi_luon_ho_so_do():
    from .test_tools_borrower import LICH_SU_DUYET, ho_so

    ma = "LA-7FA66C3814FE443887DB"
    mot_ho_so = {"data": [ho_so(status="REJECTED", funding=None)], "page": 0, "size": 10, "totalElements": 1}
    transport = route_transport({
        ("GET", f"{LOAN}/api/v1/loan-applications/me"): (200, mot_ho_so),
        ("GET", f"{LOAN}/api/v1/loan-applications/{ma}"): (200, ho_so(status="REJECTED", funding=None)),
        ("GET", f"{LOAN}/api/v1/loan-applications/{ma}/history"): (200, LICH_SU_DUYET),
    })

    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Vì sao hồ sơ của tôi bị từ chối?", "Home", "mobile"),
    )

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer.startswith(f"Hồ sơ {ma} không được duyệt")


@pytest.mark.asyncio
async def test_cau_dinh_nghia_khong_hoi_lai_ma():
    """"Lãi suất cuối là gì?" khớp từ khóa gợi ý hồ sơ nhưng không nhắc hồ sơ nào: vẫn là câu chính sách."""
    from app.services.assistant.llm.router import LlmUnavailable

    transport = route_transport({})
    llm = KichBanLlm(LlmUnavailable("het nha", [("gemini_gemma31b", "HTTP_402")]))

    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Lãi suất cuối là gì?", "Home", "mobile"),
    )

    assert "hồ sơ nào" not in phan_hoi.answer
    assert transport.requests == []
