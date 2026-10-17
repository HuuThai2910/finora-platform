"""Bảng nút điều hướng: đúng tên màn/route thật, tham số bắt buộc, chặn giá trị lạ."""

import pytest

from app.services.assistant.deeplinks import ACTIONS, allowed_action_keys, build_action

MOBILE = {
    "mo_ho_so": ("ApplicationDetail", ("applicationNumber",)),
    "mo_hop_dong": ("ContractDetail", ("contractNumber",)),
    "mo_khoan_vay": ("LoanServicingDetail", ("loanNumber",)),
    "tra_no": ("LoanPayment", ("loanNumber",)),
    "tat_toan": ("EarlySettlement", ("loanNumber",)),
    "tra_truoc": ("PartialPrepayment", ("loanNumber",)),
    "gia_han": ("RescheduleLoan", ("loanNumber",)),
    "nap_tien": ("TopUp", ()),
    "xem_san_pham": ("ProductDetail", ("productId",)),
    "nop_ho_so": ("ApplyForm", ("productId", "amount", "termMonths", "expectedDisbursementDate")),
    "mo_khoan_san": ("LoanDetail", ("loanId",)),
    "so_lenh": ("OrderBook", ("listingId",)),
    "lenh_cua_toi": ("MyBookOrders", ()),
    "auto_invest": ("AutoInvest", ()),
    "danh_muc": ("Portfolio", ()),
    "ky_hop_dong_dau_tu": ("InvestContract", ()),
    "ekyc": ("EkycCapture", ()),
    "tai_khoan": ("AccountInfo", ()),
}
WEB = {
    "trang_tong_quan": "/dashboard",
    "hang_doi_tham_dinh": "/loans?status=PENDING_REVIEW",
    "mo_tham_dinh": "/loans/{applicationNumber}/review",
    "van_hanh": "/loans/operations?tab={tab}",
    "goi_von": "/investments/funding",
    "cho_notes": "/investments/secondary",
    "chinh_sach_ai": "/loans/evaluation",
    "khach_hang_kyc": "/customers/kyc",
}


def test_bang_dung_spec_muc_13_3():
    assert set(ACTIONS) == set(MOBILE) | set(WEB)
    for key, (screen, params) in MOBILE.items():
        definition = ACTIONS[key]
        assert (definition.surface, definition.screen, definition.params) == ("mobile", screen, params)
        assert definition.path_template is None
    for key, template in WEB.items():
        definition = ACTIONS[key]
        assert (definition.surface, definition.screen, definition.path_template) == ("web", None, template)


def test_moi_hanh_dong_co_nhan_tieng_viet():
    assert all(definition.label.strip() for definition in ACTIONS.values())


def test_hanh_dong_mobile():
    action = build_action("mo_ho_so", {"applicationNumber": "LA-0F9C3C1E2A4B5C6D7E8F"})
    assert action.model_dump(by_alias=True) == {
        "key": "mo_ho_so",
        "label": "Mở hồ sơ",
        "target": {"screen": "ApplicationDetail", "params": {"applicationNumber": "LA-0F9C3C1E2A4B5C6D7E8F"}, "path": None},
    }


def test_hanh_dong_mobile_khong_tham_so_va_bo_tham_so_thua():
    action = build_action("nap_tien", {"loanNumber": "LN-1"})
    assert action.target.screen == "TopUp"
    assert action.target.params == {}


def test_nop_ho_so_du_bon_tham_so():
    action = build_action(
        "nop_ho_so",
        {"productId": "3", "amount": "30000000", "termMonths": "12", "expectedDisbursementDate": "2026-10-20"},
    )
    assert action.target.params == {
        "productId": "3", "amount": "30000000", "termMonths": "12", "expectedDisbursementDate": "2026-10-20",
    }


def test_hanh_dong_web_dien_path():
    assert build_action("mo_tham_dinh", {"applicationNumber": "LA-ABC123"}).target.path == "/loans/LA-ABC123/review"
    assert build_action("van_hanh", {"tab": "reschedule"}).target.path == "/loans/operations?tab=reschedule"
    assert build_action("hang_doi_tham_dinh").target.path == "/loans?status=PENDING_REVIEW"
    web = build_action("trang_tong_quan")
    assert (web.target.screen, web.target.params) == (None, {})


def test_doi_nhan():
    assert build_action("so_lenh", {"listingId": "12"}, label="Xem sổ lệnh khoản này").label == "Xem sổ lệnh khoản này"


def test_khoa_la():
    with pytest.raises(ValueError, match="lạ"):
        build_action("chuyen_tien", {})


@pytest.mark.parametrize(
    ("key", "params"),
    [
        ("mo_ho_so", None),
        ("mo_ho_so", {}),
        ("mo_ho_so", {"applicationNumber": ""}),
        ("van_hanh", {}),
        ("nop_ho_so", {"productId": "3", "amount": "30000000", "termMonths": "12"}),
    ],
)
def test_thieu_tham_so(key, params):
    with pytest.raises(ValueError, match="thiếu"):
        build_action(key, params)


@pytest.mark.parametrize(
    ("key", "params"),
    [
        ("mo_tham_dinh", {"applicationNumber": "../../users"}),
        ("mo_tham_dinh", {"applicationNumber": "LA-1?x=1"}),
        ("mo_ho_so", {"applicationNumber": "LA 1"}),
        ("van_hanh", {"tab": "khac"}),
    ],
)
def test_gia_tri_khong_hop_le(key, params):
    with pytest.raises(ValueError):
        build_action(key, params)


def test_khoa_theo_noi_gui():
    assert allowed_action_keys("mobile") == list(MOBILE)
    assert allowed_action_keys("web") == list(WEB)
    assert allowed_action_keys("desktop") == []
