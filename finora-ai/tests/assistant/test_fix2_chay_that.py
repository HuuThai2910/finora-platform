"""fix-2: lỗi tìm thấy khi chạy thật lần hai (16/10/2026) trên cụm finora-app với tài khoản có khoản vay thật.

Lần chạy trước (Task 21, fix-1) không có người vay nào đăng nhập được mà có khoản đã giải ngân, nên các nhánh
khoản vay quá hạn, ví so với kỳ trả, hồ sơ đã giải ngân chưa từng chạy với dữ liệu thật. Lần này dùng người
vay mẫu (3 khoản, 2 quá hạn 26/58 ngày) và tìm ra:

1. "Kỳ tới" in ngày đã qua của khoản quá hạn (19/08/2026 khi hôm nay là 16/10/2026); ví "đủ trả kỳ tới" chỉ
   so với MỘT kỳ trong khi khoản đang quá hạn hai kỳ (nói "cần nạp ít nhất 8.955.000 đ" thay vì 17.910.000 đ).
2. Màn chi tiết khoản vay gửi `loanNumber` nhưng trợ lý trả lời về khoản khác (khoản quá hạn lâu nhất).
3. Hồ sơ đã giải ngân vẫn được nói "bước tiếp theo là ký hợp đồng"; hồ sơ đã từ chối điều khoản vẫn được nói
   "tiếp theo là gọi vốn" — ngược với app (`applicationJourneyStatus` của finora-mobile).
4. "Hồ sơ LA-… đang ở bước nào?" (gõ mã) không ra dữ liệu khi không có LLM.
5. Giải thích mã lỗi vừa gặp (lastErrorCode) bị mất khi câu trả lời là câu mẫu dự phòng.
6. "Nạp 5 triệu vào ví giúp tôi" không bị từ chối làm thay.
7. Nhà đầu tư hỏi "khoản nào quá hạn", "Note nợ xấu là gì" bị đưa sang công cụ thông báo.
8. Căn cứ không liên quan (kèm câu miễn trừ pháp lý) gắn vào câu mẫu dự phòng; căn cứ trùng mã.
9. Câu cần dữ liệu (C3) khi không có LLM nhận một đoạn kiến thức không liên quan.
10. Nút "Mở hồ sơ" trỏ vào hồ sơ không xem được sau lỗi 403/404; dấu câu ".;" trong tóm tắt hồ sơ.
"""

from __future__ import annotations

import json
from datetime import date

import pytest

from app.schemas.assistant import ChatRequest, ChatTurn, ScreenContext
from app.services.assistant.glossary import dich_trang_thai
from app.services.assistant.knowledge.bm25 import get_index
from app.services.assistant.legal import load_legal_sources
from app.services.assistant.llm.router import LlmResult, LlmUnavailable
from app.services.assistant.orchestrator import AssistantOrchestrator
from app.services.assistant.principal import Toolset
from app.services.assistant.thao_tac import detect_action_request
from app.services.assistant.tools import borrower
from app.services.assistant.tools.admin import ADMIN_SUGGESTIONS
from app.services.assistant.tools.investor import INVESTOR_SUGGESTIONS
from app.services.assistant.tools.registry import default_registry

from .helpers import TEST_URLS, make_principal, make_settings, route_transport, run_tool
from .test_orchestrator import DongHo, KichBanLlm
from .test_tools_borrower import (
    LICH_SU_DUYET,
    MA_HO_SO,
    MA_HO_SO_CU,
    MA_HOP_DONG,
    MA_KHOAN_VAY,
    MA_KHOAN_VAY_2,
    SPECS,
    SUGGESTIONS,
    URL_DS_HO_SO,
    URL_HO_SO,
    URL_HOP_DONG,
    URL_KHOAN_VAY,
    URL_LICH_SU,
    URL_VI,
    VI,
    ho_so,
    khoan,
    trang_khoan,
)

LOAN = TEST_URLS["loan"]
NGUOI_VAY = frozenset({Toolset.BORROWER})
NHA_DAU_TU = frozenset({Toolset.INVESTOR})
QUAN_TRI = frozenset({Toolset.ADMIN})
TRANG_RONG = {"data": [], "page": 0, "size": 10, "totalElements": 0}
HOM_NAY = date(2026, 10, 16)
KHONG_CO_LLM = LlmUnavailable("không có nhà", [("*", "KHONG_CO_NHA")])


@pytest.fixture(autouse=True)
def _co_dinh_hom_nay(monkeypatch):
    monkeypatch.setattr(borrower, "_hom_nay_vn", lambda: HOM_NAY)


def _hai_khoan_qua_han_va_mot_dung_han():
    return trang_khoan(
        khoan(MA_KHOAN_VAY, ky_toi="2026-08-19", so_tien=8955000, qua_han=17910000, dpd=58),
        khoan(MA_KHOAN_VAY_2, ky_toi="2026-11-11", so_tien=2942000),
    )


def _trang(*items):
    return {"data": list(items), "page": 0, "size": 10, "totalElements": len(items)}


# ───────────────────────────────────────────── 1. Kỳ đã qua không phải "kỳ tới"

@pytest.mark.asyncio
async def test_ky_da_qua_cua_khoan_qua_han_khong_goi_la_ky_toi():
    result = await run_tool(SPECS["khoan_vay_cua_toi"], {}, {URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})

    qua_han = next(k for k in result.facts["khoan_vay"] if k["ma_khoan_vay"] == MA_KHOAN_VAY)
    assert "ky_toi" not in qua_han
    assert qua_han["ky_chua_tra"] == {"ngay": "19/08/2026", "so_tien": "8.955.000 đ"}
    hang = {r[0]: r for r in result.cards[0].rows}
    assert hang[MA_KHOAN_VAY][2] == "19/08/2026 · 8.955.000 đ (đã quá hạn)"
    assert hang[MA_KHOAN_VAY_2][2] == "11/11/2026 · 2.942.000 đ"

    cau = SUGGESTIONS["borrower.loan.next_due"].render(result)
    assert f"Kỳ tới của khoản {MA_KHOAN_VAY} " not in cau
    assert f"Kỳ tới của khoản {MA_KHOAN_VAY_2} là ngày 11/11/2026, số tiền 2.942.000 đ" in cau
    assert "quá hạn 58 ngày" in cau and "17.910.000 đ" in cau


@pytest.mark.asyncio
async def test_chi_co_khoan_qua_han_thi_khong_noi_ky_toi():
    result = await run_tool(SPECS["khoan_vay_cua_toi"], {}, {URL_KHOAN_VAY: (200, trang_khoan(
        khoan(MA_KHOAN_VAY, ky_toi="2026-09-20", so_tien=2672000, qua_han=2672000, dpd=26)))})

    cau = SUGGESTIONS["borrower.loan.next_due"].render(result)
    assert "quá hạn 26 ngày" in cau and "2.672.000 đ" in cau
    assert "Kỳ tới" not in cau
    assert "20/09/2026" not in cau


# ───────────────────────────────────────────── 2. Khoản đang mở trên màn

@pytest.mark.asyncio
async def test_khoan_dang_mo_tren_man_duoc_tra_loi_truoc():
    result = await run_tool(SPECS["khoan_vay_cua_toi"], {"loanNumber": MA_KHOAN_VAY_2},
                            {URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})

    assert result.facts["khoan_dang_mo"] == MA_KHOAN_VAY_2
    assert result.facts["khoan_vay"][0]["ma_khoan_vay"] == MA_KHOAN_VAY_2
    cau = SUGGESTIONS["borrower.loan.next_due"].render(result)
    assert cau.startswith(f"Kỳ tới của khoản {MA_KHOAN_VAY_2} là ngày 11/11/2026, số tiền 2.942.000 đ")
    assert "1 khoản khác đang quá hạn" in cau
    assert [(a.key, a.target.params) for a in result.actions] == [("tra_no", {"loanNumber": MA_KHOAN_VAY_2})]


@pytest.mark.asyncio
async def test_ma_khoan_tren_man_khong_phai_cua_minh_thi_bo_qua():
    result = await run_tool(SPECS["khoan_vay_cua_toi"], {"loanNumber": "LN-KHONG0CUA0MINH0000"},
                            {URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})
    assert "khoan_dang_mo" not in result.facts
    assert "LN-KHONG0CUA0MINH0000" not in json.dumps(result.facts, ensure_ascii=False)
    assert all("LN-KHONG0CUA0MINH0000" not in a.target.params.values() for a in result.actions)


# ───────────────────────────────────────────── 1b. Ví so với phần quá hạn

@pytest.mark.asyncio
async def test_vi_so_voi_phan_qua_han_khi_khoan_dang_qua_han():
    result = await run_tool(SPECS["vi_cua_toi"], {}, {
        URL_VI: (200, dict(VI, available=1000000)),
        URL_KHOAN_VAY: (200, trang_khoan(khoan(MA_KHOAN_VAY, ky_toi="2026-08-19", so_tien=8955000,
                                               qua_han=17910000, dpd=58)))})

    assert result.facts["qua_han"] == {"ma_khoan_vay": MA_KHOAN_VAY, "so_tien": "17.910.000 đ", "so_ngay": 58}
    assert result.facts["con_thieu"] == "16.910.000 đ"
    assert "ky_toi" not in result.facts
    the = {i.label: i.value for i in result.cards[0].items}
    assert the["Phần quá hạn"] == "17.910.000 đ (58 ngày)"
    assert the["Còn thiếu"] == "16.910.000 đ"
    assert [a.key for a in result.actions] == ["nap_tien"]
    cau = SUGGESTIONS["borrower.wallet.enough"].render(result)
    assert "đang quá hạn 58 ngày" in cau and "17.910.000 đ" in cau and "16.910.000 đ" in cau
    assert "Kỳ tới" not in cau


@pytest.mark.asyncio
async def test_vi_du_tra_phan_qua_han():
    result = await run_tool(SPECS["vi_cua_toi"], {}, {
        URL_VI: (200, dict(VI, available=20000000)),
        URL_KHOAN_VAY: (200, trang_khoan(khoan(MA_KHOAN_VAY, ky_toi="2026-08-19", so_tien=8955000,
                                               qua_han=17910000, dpd=58)))})
    assert result.facts["du_tra_qua_han"] == "có"
    assert [(a.key, a.target.params) for a in result.actions] == [("tra_no", {"loanNumber": MA_KHOAN_VAY})]
    assert "đủ để trả phần quá hạn" in SUGGESTIONS["borrower.wallet.enough"].render(result)


@pytest.mark.asyncio
async def test_vi_so_voi_khoan_dang_mo_tren_man():
    result = await run_tool(SPECS["vi_cua_toi"], {"loanNumber": MA_KHOAN_VAY_2}, {
        URL_VI: (200, dict(VI, available=1000000)), URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})
    assert result.facts["ky_toi"] == {"ma_khoan_vay": MA_KHOAN_VAY_2, "ngay": "11/11/2026", "so_tien": "2.942.000 đ"}
    assert result.facts["con_thieu"] == "1.942.000 đ"
    assert "qua_han" not in result.facts


# ───────────────────────────────────────────── 3. Giai đoạn hồ sơ khớp app

def _ho_so_du_von(**thay):
    return ho_so(funding={"status": "FULLY_FUNDED", "fundingRound": 1, "listingVersion": 1,
                          "requestedAt": "2026-07-10T09:32:34Z", "investmentListingId": 44, "fundedAmount": 30000000,
                          "fullyFundedAt": "2026-07-10T16:32:34Z", "expiredAt": None},
                 termsConfirmation={"status": "AUTO_AUTHORIZED", "termsVersion": "LOAN_TERMS_V1", "termsHash": "x",
                                    "expiresAt": "2026-07-17T09:32:34Z", "respondedAt": "2026-07-10T09:32:34Z",
                                    "declineReasonCode": None, "declineReasonDetail": None}, **thay)


async def _chi_tiet(ho_so_json, hop_dong=TRANG_RONG, khoan_vay=TRANG_RONG):
    return await run_tool(SPECS["chi_tiet_ho_so"], {"applicationNumber": MA_HO_SO}, {
        URL_HO_SO: (200, ho_so_json), URL_LICH_SU: (200, LICH_SU_DUYET),
        URL_HOP_DONG: (200, hop_dong), URL_KHOAN_VAY: (200, khoan_vay)})


@pytest.mark.asyncio
async def test_ho_so_da_giai_ngan_khong_noi_buoc_tiep_theo_la_ky():
    result = await _chi_tiet(_ho_so_du_von(), khoan_vay=trang_khoan(khoan(MA_KHOAN_VAY)))

    assert result.facts["khoan_vay"] == {"ma_khoan_vay": MA_KHOAN_VAY, "trang_thai": dich_trang_thai("loan", "ACTIVE")}
    assert result.facts["giai_doan"]["nhan"] == "Đã giải ngân"
    assert ("mo_khoan_vay", {"loanNumber": MA_KHOAN_VAY}) in [(a.key, a.target.params) for a in result.actions]

    chua_nhan = SUGGESTIONS["borrower.application.not_disbursed"].render(result)
    assert "đã được giải ngân" in chua_nhan and MA_KHOAN_VAY in chua_nhan
    assert "ký hợp đồng" not in chua_nhan
    trang_thai = SUGGESTIONS["borrower.application.detail_status"].render(result)
    assert "Đã giải ngân" in trang_thai and MA_KHOAN_VAY in trang_thai


@pytest.mark.asyncio
async def test_ho_so_da_tu_choi_dieu_khoan_khong_len_san():
    tu_choi = ho_so(funding=None, termsConfirmation={
        "status": "DECLINED", "termsVersion": "LOAN_TERMS_V1", "termsHash": "x", "expiresAt": "2026-10-17T19:56:36Z",
        "respondedAt": "2026-10-10T19:57:12Z", "declineReasonCode": "TERMS_NOT_ACCEPTED", "declineReasonDetail": "QA"})
    result = await _chi_tiet(tu_choi)

    assert result.facts["giai_doan"]["nhan"] == "Đã từ chối điều khoản"
    for goi_y in ("borrower.application.not_disbursed", "borrower.application.why_rejected",
                  "borrower.application.detail_status"):
        cau = SUGGESTIONS[goi_y].render(result)
        assert "không lập hợp đồng" in cau, goi_y
        assert "gọi vốn từ nhà đầu tư" not in cau and "chờ được đưa lên sàn" not in cau, goi_y
    assert "QA" not in json.dumps(result.facts, ensure_ascii=False)


@pytest.mark.asyncio
async def test_ho_so_het_han_xac_nhan_dieu_khoan():
    het_han = ho_so(funding=None, termsConfirmation={
        "status": "EXPIRED", "termsVersion": "LOAN_TERMS_V1", "termsHash": "x", "expiresAt": "2026-10-01T00:00:00Z",
        "respondedAt": None, "declineReasonCode": None, "declineReasonDetail": None})
    result = await _chi_tiet(het_han)
    assert result.facts["giai_doan"]["nhan"] == "Hết hạn phản hồi"
    assert "không lập hợp đồng" in SUGGESTIONS["borrower.application.not_disbursed"].render(result)


@pytest.mark.asyncio
async def test_ho_so_du_von_chua_co_hop_dong_va_co_hop_dong_cho_ky():
    chua_hd = await _chi_tiet(_ho_so_du_von())
    assert chua_hd.facts["giai_doan"]["nhan"] == "Đang lập hợp đồng"
    assert "đang lập hợp đồng" in SUGGESTIONS["borrower.application.not_disbursed"].render(chua_hd)

    hop_dong = _trang({"contractNumber": MA_HOP_DONG, "applicationNumber": MA_HO_SO, "principalAmount": 30000000,
                       "termMonths": 12, "annualInterestRate": 15.5, "totalRepayment": 32500000,
                       "status": "PENDING_BORROWER_SIGNATURE", "expiresAt": "2026-10-18T10:00:00Z", "version": 2,
                       "createdAt": "2026-10-14T08:00:00Z"})
    cho_ky = await _chi_tiet(_ho_so_du_von(), hop_dong=hop_dong)
    assert cho_ky.facts["hop_dong"] == {"ma_hop_dong": MA_HOP_DONG,
                                        "trang_thai": dich_trang_thai("contract", "PENDING_BORROWER_SIGNATURE")}
    assert cho_ky.facts["giai_doan"]["nhan"] == dich_trang_thai("contract", "PENDING_BORROWER_SIGNATURE")
    assert ("mo_hop_dong", {"contractNumber": MA_HOP_DONG}) in [(a.key, a.target.params) for a in cho_ky.actions]


@pytest.mark.asyncio
async def test_ho_so_chua_duyet_khong_tra_cuu_hop_dong_khoan_vay():
    """Chỉ hồ sơ đã duyệt mới có hợp đồng/khoản vay: hồ sơ khác không tốn thêm request."""
    result = await run_tool(SPECS["chi_tiet_ho_so"], {"applicationNumber": MA_HO_SO}, {
        URL_HO_SO: (200, ho_so(status="PENDING_REVIEW", funding=None)), URL_LICH_SU: (200, LICH_SU_DUYET)})
    assert "giai_doan" not in result.facts


@pytest.mark.asyncio
async def test_danh_sach_ho_so_hien_giai_doan_nhu_app():
    trang = {"data": [_ho_so_du_von(), ho_so(applicationNumber=MA_HO_SO_CU, status="REJECTED", funding=None,
                                            termsConfirmation=None)],
             "page": 0, "size": 10, "totalElements": 2}
    result = await run_tool(SPECS["ho_so_cua_toi"], {}, {
        URL_DS_HO_SO: (200, trang), URL_HOP_DONG: (200, TRANG_RONG),
        URL_KHOAN_VAY: (200, trang_khoan(khoan(MA_KHOAN_VAY)))})

    assert result.facts["ho_so"][0]["trang_thai"] == "Đã giải ngân"
    assert result.facts["ho_so"][1]["trang_thai"] == dich_trang_thai("application", "REJECTED")
    assert result.cards[0].rows[0][1] == "Đã giải ngân"
    cau = SUGGESTIONS["borrower.application.status"].render(result)
    assert "Đã giải ngân" in cau
    assert "chưa phải giải ngân" not in cau and "tiếp theo là gọi vốn" not in cau


@pytest.mark.asyncio
async def test_danh_sach_khong_co_ho_so_da_duyet_thi_chi_mot_request():
    trang = {"data": [ho_so(status="REJECTED", funding=None, termsConfirmation=None)], "page": 0, "size": 10,
             "totalElements": 1}
    result = await run_tool(SPECS["ho_so_cua_toi"], {}, {URL_DS_HO_SO: (200, trang)})
    assert result.facts["ho_so"][0]["trang_thai"] == dich_trang_thai("application", "REJECTED")


def test_goi_y_trang_thai_mot_ho_so_tren_man_chi_tiet():
    goi_y = SUGGESTIONS["borrower.application.detail_status"]
    assert goi_y.tool == "chi_tiet_ho_so"
    assert goi_y.params_from_context == {"applicationNumber": "applicationNumber"}
    assert goi_y.screens == frozenset({"ApplicationDetail"})
    default_registry.cache_clear()
    ids = [g.id for g in default_registry().suggestions_for(NGUOI_VAY, "ApplicationDetail")]
    assert ids[0] == "borrower.application.detail_status"
    assert SUGGESTIONS["borrower.application.status"].khi_co_ma == "borrower.application.detail_status"


# ───────────────────────────────────────────── orchestrator với registry + kho kiến thức thật

def _dung(llm, transport) -> AssistantOrchestrator:
    default_registry.cache_clear()
    return AssistantOrchestrator(make_settings(), default_registry(), get_index(), llm, load_legal_sources(),
                                 transport=transport, clock=DongHo())


def _hoi(message, screen=None, params=None, surface="mobile", last_error=None, history=(), sid=None):
    ctx = ScreenContext(screen=screen, params=params or {}, last_error_code=last_error)
    return ChatRequest(message=message, history=[ChatTurn(role=r, content=c) for r, c in history], surface=surface,
                       context=ctx, suggestion_id=sid)


def _duong(transport):
    return [r.url.path for r in transport.requests]


@pytest.mark.asyncio
async def test_man_chi_tiet_khoan_vay_tra_loi_ve_khoan_dang_mo():
    transport = route_transport({URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Kỳ tới khoản này tôi phải trả bao nhiêu?", "LoanServicingDetail", {"loanNumber": MA_KHOAN_VAY_2}))

    assert f"Kỳ tới của khoản {MA_KHOAN_VAY_2} là ngày 11/11/2026" in phan_hoi.answer
    assert phan_hoi.actions[0].target.params == {"loanNumber": MA_KHOAN_VAY_2}


@pytest.mark.asyncio
async def test_goi_y_vi_tren_man_chi_tiet_khoan_vay_dung_khoan_dang_mo():
    transport = route_transport({URL_VI: (200, dict(VI, available=0)),
                                 URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})
    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Ví có đủ trả kỳ tới không?", "LoanServicingDetail", {"loanNumber": MA_KHOAN_VAY_2},
             sid="borrower.wallet.enough"))
    assert phan_hoi.mode == "template"
    assert MA_KHOAN_VAY_2 in phan_hoi.answer and "2.942.000 đ" in phan_hoi.answer
    assert MA_KHOAN_VAY not in phan_hoi.answer


@pytest.mark.asyncio
async def test_go_ma_ho_so_hoi_buoc_nao_tra_loi_bang_chi_tiet_ho_so():
    url_ho_so_cu = ("GET", f"{LOAN}/api/v1/loan-applications/{MA_HO_SO_CU}")
    url_lich_su_cu = ("GET", f"{LOAN}/api/v1/loan-applications/{MA_HO_SO_CU}/history")
    transport = route_transport({
        url_ho_so_cu: (200, ho_so(applicationNumber=MA_HO_SO_CU, status="PENDING_REVIEW", funding=None)),
        url_lich_su_cu: (200, LICH_SU_DUYET)})
    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi(f"Hồ sơ {MA_HO_SO_CU} đang ở bước nào?", "Home"))

    assert phan_hoi.mode == "template"
    assert MA_HO_SO_CU in phan_hoi.answer
    assert dich_trang_thai("application", "PENDING_REVIEW") in phan_hoi.answer
    assert "/api/v1/loan-applications/me" not in _duong(transport)


@pytest.mark.asyncio
async def test_hoi_buoc_nao_khong_ma_van_tra_danh_sach_ho_so():
    transport = route_transport({URL_DS_HO_SO: (200, {"data": [ho_so(status="REJECTED", funding=None,
                                                                         termsConfirmation=None)],
                                                       "page": 0, "size": 10, "totalElements": 1})})
    phan_hoi = await _dung(KichBanLlm(), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Hồ sơ của tôi đang ở bước nào?", "Home"))
    assert phan_hoi.mode == "template"
    assert _duong(transport) == ["/api/v1/loan-applications/me"]
    assert "Hồ sơ mới nhất của bạn" in phan_hoi.answer


@pytest.mark.asyncio
async def test_cau_hoi_dinh_nghia_khong_bi_goi_y_du_lieu_chiem():
    transport = route_transport({})
    llm = KichBanLlm(KHONG_CO_LLM)
    for vai_tro, roles, cau in (("ROLE_INVESTOR", NHA_DAU_TU, "Note nợ xấu là gì?"),
                                ("ROLE_BORROWER", NGUOI_VAY, "Nhóm nợ là gì?")):
        llm = KichBanLlm(KHONG_CO_LLM)
        phan_hoi = await _dung(llm, transport).handle(make_principal(vai_tro), roles, _hoi(cau, "Home"))
        assert transport.requests == [], cau
        assert "Chưa lấy được dữ liệu" not in phan_hoi.answer, cau


def test_nha_dau_tu_hoi_khoan_qua_han_khop_danh_muc_khong_khop_thong_bao():
    goi_y = {g.id: g for g in INVESTOR_SUGGESTIONS}
    assert not {"quá hạn", "nợ xấu", "nhóm nợ", "dpd"} & set(goi_y["investor.notifications.explain"].keywords)
    assert {"quá hạn", "nợ xấu"} <= set(goi_y["investor.portfolio.summary"].keywords)


@pytest.mark.asyncio
async def test_giai_thich_ma_loi_vua_gap_giu_trong_cau_du_phong():
    transport = route_transport({URL_HOP_DONG: (200, TRANG_RONG)})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Vì sao tôi không làm được?", "ContractDetail", last_error="PIN_LOCKED"))
    assert phan_hoi.mode == "fallback"
    assert "Mã PIN" in phan_hoi.answer and "khóa" in phan_hoi.answer


@pytest.mark.asyncio
async def test_cau_mau_du_phong_khong_gan_can_cu_cua_doan_khong_dung():
    transport = route_transport({URL_HOP_DONG: (200, TRANG_RONG)})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Hợp đồng này đang chờ ai ký?", "ContractDetail", {"contractNumber": MA_HOP_DONG}))
    assert phan_hoi.mode == "fallback"
    assert phan_hoi.citations == []
    assert phan_hoi.disclaimer is None


@pytest.mark.asyncio
async def test_cau_can_du_lieu_khong_co_llm_khong_tra_doan_khong_lien_quan():
    transport = route_transport({})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Tôi đã trả được bao nhiêu kỳ rồi?", "Home"))
    assert phan_hoi.mode == "fallback"
    assert "chế độ rút gọn" in phan_hoi.answer
    assert "Mỗi sản phẩm vay" not in phan_hoi.answer
    assert phan_hoi.citations == []


@pytest.mark.asyncio
async def test_cau_chinh_sach_co_chu_toi_khop_manh_van_dua_doan_kien_thuc():
    """Câu chính sách có "tôi" (đi đường C3) mà đoạn khớp mạnh thì vẫn trả thân đoạn + căn cứ (ca NDT-CS-05)."""
    transport = route_transport({})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_INVESTOR"), NHA_DAU_TU,
        _hoi("Khoản vay không gọi đủ vốn thì tiền tôi đã góp sẽ thế nào?", "Home"))
    assert phan_hoi.mode == "fallback"
    assert "trả lại" in phan_hoi.answer
    assert any(c.kind == "CHINH_SACH" for c in phan_hoi.citations)


@pytest.mark.asyncio
async def test_guard_chan_cau_llm_thi_dung_cau_mau_cua_cong_cu_khong_in_khoa_tho():
    transport = route_transport({URL_KHOAN_VAY: (200, _hai_khoan_qua_han_va_mot_dung_han())})
    llm = KichBanLlm(
        LlmResult(text=None, tool_calls=[__import__("app.services.assistant.llm.router", fromlist=["ToolCall"])
                                         .ToolCall(id="c1", name="khoan_vay_cua_toi", arguments={})],
                  provider="p", model="m", prompt_tokens=1, completion_tokens=1),
        LlmResult(text=json.dumps({"answer": "Bạn cần trả 99.999.000 đ.", "actions": []}), tool_calls=[],
                  provider="p", model="m", prompt_tokens=1, completion_tokens=1),
    )
    phan_hoi = await _dung(llm, transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, _hoi("Kịch bản: tháng này của tôi thế nào?", "Home"))
    assert phan_hoi.mode == "fallback"
    assert "99.999.000" not in phan_hoi.answer
    assert "Tong so" not in phan_hoi.answer and "Ma khoan vay" not in phan_hoi.answer
    assert "quá hạn 58 ngày" in phan_hoi.answer


@pytest.mark.asyncio
async def test_ho_so_khong_xem_duoc_thi_khong_co_nut_mo():
    transport = route_transport({URL_HO_SO: (403, {"code": "LOAN_APPLICATION_ACCESS_DENIED"})})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        _hoi("Hồ sơ này đang ở bước nào?", "ApplicationDetail", {"applicationNumber": MA_HO_SO}))
    assert "Không tìm thấy" in phan_hoi.answer
    assert phan_hoi.actions == []


@pytest.mark.asyncio
async def test_danh_sach_ho_so_tab_tat_ca_van_di_duong_man_hinh():
    url = ("GET", f"{LOAN}/api/v1/admin/loan-applications")
    transport = route_transport({url: (200, {"data": [], "page": 0, "size": 20, "totalElements": 0})})
    phan_hoi = await _dung(KichBanLlm(KHONG_CO_LLM), transport).handle(
        make_principal("ROLE_ADMIN"), QUAN_TRI, _hoi("Danh sách này có gì đáng chú ý?", "LoanList", surface="web"))
    assert _duong(transport) == ["/api/v1/admin/loan-applications"]
    assert transport.requests[0].url.params["status"] == "PENDING_REVIEW"
    assert phan_hoi.mode == "fallback"


def test_can_cu_khong_trung_ma():
    from app.schemas.assistant import Citation
    from app.services.assistant.orchestrator import _bo_trung_can_cu
    a = Citation(id="CS-THAM-DINH-01", kind="CHINH_SACH", label="Chính sách thẩm định FINORA CREDIT_POLICY_V1",
                 checked_at="2026-10-15")
    b = Citation(id="CS-THAM-DINH-01", kind="CHINH_SACH", label="Chính sách thẩm định", checked_at="2026-10-15")
    assert _bo_trung_can_cu([a, b]) == [a]


# ───────────────────────────────────────────── 6. Nạp/rút có số tiền

@pytest.mark.parametrize("cau", [
    "Nạp 5 triệu vào ví giúp tôi", "nap 500k vao vi giup minh", "Rút 2 triệu về tài khoản ngân hàng giúp tôi",
])
def test_nap_rut_co_so_tien_la_yeu_cau_lam_thay(cau):
    assert detect_action_request(cau, NGUOI_VAY) is not None
    assert detect_action_request(cau, NHA_DAU_TU) is not None


@pytest.mark.parametrize("cau", ["Nạp tiền vào ví thế nào?", "Rút tiền mất bao lâu?", "Ví còn 5 triệu phải không?"])
def test_hoi_cach_nap_rut_khong_bi_tu_choi(cau):
    assert detect_action_request(cau, NGUOI_VAY) is None


# ───────────────────────────────────────────── 10. Dấu câu tóm tắt hồ sơ (quản trị)

def test_tom_tat_ho_so_khong_co_dau_cham_phay_lien_nhau():
    from app.services.assistant.tools.base import ToolResult
    goi_y = {g.id: g for g in ADMIN_SUGGESTIONS}["admin.review.summary"]
    cau = goi_y.render(ToolResult(facts=_facts_tom_tat(), cards=[]))
    assert ".;" not in cau and ".." not in cau
    assert "Lịch sử ghi nhận: Hệ thống bắt đầu chấm điểm tín dụng; Người vay đã nộp hồ sơ." in cau
    assert "Lý do từ chối: Khả năng trả nợ chưa đạt." in cau


def _facts_tom_tat():
    """Facts tối thiểu của tom_tat_ho_so theo hình dạng thật (số liệu giả)."""
    return {
        "ma_ho_so": MA_HO_SO, "trang_thai": "Chờ thẩm định",
        "khoan_de_nghi": {"so_tien": "25.000.000 đ", "ky_han": "3 tháng"},
        "cham_diem": {"diem_tong_hop": "72,21", "hang": "B", "xac_suat_vo_no": "29,16%", "ai_de_xuat": "Chờ thẩm định",
                      "trang_thai": "Đã chấm", "ly_do_tu_choi": ["Khả năng trả nợ chưa đạt."]},
        "tai_chinh_khoang": {"thu_nhap_thang": "35–50 triệu/tháng", "nghia_vu_no_thang": "dưới 2 triệu/tháng",
                             "dti": "dưới 10%"},
        "luat": [{"ma": "R1", "mo_ta": "Thiếu lịch sử CIC.", "diem": "0/20", "thieu_du_lieu": "có"}],
        "ly_do_trong_lich_su": [{"y_nghia": "Hệ thống bắt đầu chấm điểm tín dụng."},
                                {"y_nghia": "Người vay đã nộp hồ sơ."}],
    }
