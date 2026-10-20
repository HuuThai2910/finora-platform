"""Test từ điển mã của trợ lý (AI-CHAT-001, Task 7).

Bất biến quan trọng nhất: MỌI giá trị enum Java đang có đều phải có nhãn tiếng Việt.
Test đọc thẳng file enum trong finora-platform, nên ai thêm trạng thái mới ở Java mà quên
bổ sung glossary thì test gãy — thay vì người dùng thấy "(chưa có diễn giải)" trong chat.
"""

import os
import re
from pathlib import Path

import pytest

from app.ml.credit.dien_giai import DIEN_GIAI_CHOT_CHAN
from app.services.assistant import glossary
from app.services.assistant.glossary import (
    CHUA_CO_DIEN_GIAI,
    KHONG_CO,
    can_cu_ly_do,
    dich_danh_sach_ly_do,
    dich_ly_do,
    dich_ma_loi,
    dich_trang_thai,
    giai_thich_trang_thai,
    la_no_xau,
    nhom_no_tu_dpd,
    ten_nhom_no,
)


def _goc_platform() -> Path | None:
    """Thư mục finora-platform: biến môi trường, hoặc dò ngược từ vị trí file test.

    Image Docker của finora-ai không chứa mã Java; khi đó test enum tự bỏ qua.
    """
    tu_env = os.environ.get("FINORA_PLATFORM_DIR")
    if tu_env:
        return Path(tu_env)
    for cha in Path(__file__).resolve().parents:
        if (cha / "finora-loan").is_dir() and (cha / "finora-common").is_dir():
            return cha
    return None


_LOAN = "finora-loan/src/main/java/com/finora/loan/domain"
_COMMON = "finora-common/src/main/java/com/finora/common/enums"
_INVEST = "finora-investment/src/main/java/com/finora/investment/domain"

ENUM_THEO_NHOM: dict[str, list[str]] = {
    "application": [f"{_LOAN}/application/LoanApplicationStatus.java"],
    "contract": [f"{_LOAN}/contract/LoanContractStatus.java"],
    "loan": [f"{_LOAN}/servicing/FinoraLoanStatus.java"],
    "funding": [f"{_LOAN}/application/LoanFundingStatus.java"],
    "terms": [f"{_LOAN}/application/TermsConfirmationStatus.java"],
    "listing": [f"{_COMMON}/investment/ListingStatus.java"],
    "commitment": [f"{_COMMON}/investment/CommitmentStatus.java"],
    "note": [f"{_COMMON}/investment/NoteStatus.java"],
    "book_order": [f"{_INVEST}/orderbook/BookOrderStatus.java"],
    "primary_order": [f"{_COMMON}/investment/OrderStatus.java"],
    "collection": [
        f"{_LOAN}/collection/CollectionStage.java",
        f"{_LOAN}/collection/CollectionCaseStatus.java",
    ],
    "reschedule": [f"{_LOAN}/restructure/LoanRescheduleStatus.java"],
    "disbursement": [f"{_LOAN}/disbursement/DisbursementSagaStatus.java"],
    "ekyc": [f"{_COMMON}/user/EkycStatus.java", f"{_COMMON}/user/EkycResultCode.java"],
}

ENUM_LY_DO = [
    f"{_LOAN}/decision/AdminDecisionReasonCode.java",
    f"{_INVEST}/orderbook/CancelReason.java",
]


def _hang_enum(path: Path) -> list[str]:
    """Tên hằng của enum Java: phần thân trước dấu `;` (hoặc `}`) đầu tiên, đã bỏ comment."""
    nguon = path.read_text(encoding="utf-8")
    nguon = re.sub(r"/\*.*?\*/", "", nguon, flags=re.DOTALL)
    nguon = re.sub(r"//[^\n]*", "", nguon)
    than = nguon.split("{", 1)[1]
    than = re.split(r"[;}]", than, maxsplit=1)[0]
    than = re.sub(r"\([^)]*\)", "", than)  # POLICY_APPROVED(true) -> POLICY_APPROVED
    return re.findall(r"\b[A-Z][A-Z0-9_]*\b", than)


def _doc_enum(rel: str) -> list[str]:
    goc = _goc_platform()
    if goc is None or not (goc / rel).is_file():
        pytest.skip(f"Không có mã Java để đối chiếu: {rel}")
    hang = _hang_enum(goc / rel)
    assert hang, f"Không đọc được hằng nào trong {rel}"
    return hang


@pytest.mark.parametrize("nhom", sorted(ENUM_THEO_NHOM))
def test_moi_gia_tri_enum_java_deu_co_nhan_va_giai_thich(nhom):
    for rel in ENUM_THEO_NHOM[nhom]:
        for ma in _doc_enum(rel):
            assert CHUA_CO_DIEN_GIAI not in dich_trang_thai(nhom, ma), (nhom, ma)
            assert giai_thich_trang_thai(nhom, ma), (nhom, ma)


@pytest.mark.parametrize("rel", ENUM_LY_DO)
def test_ma_ly_do_admin_va_ly_do_huy_lenh_deu_co_cau(rel):
    for ma in _doc_enum(rel):
        assert CHUA_CO_DIEN_GIAI not in dich_ly_do(ma), ma


def test_doc_enum_bo_tham_so_va_comment():
    goc = _goc_platform()
    if goc is None:
        pytest.skip("Không có mã Java")
    assert _hang_enum(goc / f"{_LOAN}/decision/AdminDecisionReasonCode.java")[:2] == [
        "POLICY_APPROVED",
        "INSUFFICIENT_REPAYMENT_CAPACITY",
    ]
    assert _hang_enum(goc / f"{_LOAN}/application/LoanFundingStatus.java") == [
        "REQUESTED",
        "FULLY_FUNDED",
        "EXPIRED",
        "CANCELLED",
    ]


@pytest.mark.parametrize(
    "nhom, ma, nhan",
    [
        ("application", "PENDING_REVIEW", "Chờ thẩm định"),
        ("application", "REJECTED", "Không được duyệt"),
        ("contract", "PENDING_BORROWER_SIGNATURE", "Chờ người vay ký"),
        ("loan", "DEFAULTED", "Nợ xấu"),
        ("funding", "REQUESTED", "Đang gọi vốn"),
        ("terms", "PENDING", "Chờ người vay xác nhận"),
        ("listing", "DRAFT", "Chờ quản trị duyệt"),
        ("commitment", "FINALIZED", "Đã khóa vốn"),
        ("note", "CLOSED", "Đã tất toán"),
        ("book_order", "PARTIALLY_FILLED", "Khớp một phần"),
        ("primary_order", "COMMITTED", "Đã góp vốn"),
        ("collection", "NPL", "Nợ xấu"),
        ("collection", "CURED", "Đã khắc phục"),
        ("reschedule", "APPROVING", "Đang áp dụng lịch mới"),
        ("disbursement", "REPAIR_REQUIRED", "Cần xử lý tay"),
        ("pin", "LOCKED", "Mã PIN đang tạm khóa"),
        ("ekyc", "OCR_FAILED", "Không đọc được CCCD"),
    ],
)
def test_nhan_trang_thai_tung_nhom(nhom, ma, nhan):
    assert dich_trang_thai(nhom, ma) == nhan


def test_da_duyet_giai_thich_chua_phai_giai_ngan():
    # Câu hỏi số một của người vay: "đã duyệt sao chưa nhận tiền?"
    assert "chưa phải là đã giải ngân" in giai_thich_trang_thai("application", "APPROVED")


def test_trang_thai_thieu_ma_la_va_chu_thuong():
    assert dich_trang_thai("loan", None) == KHONG_CO
    assert dich_trang_thai("loan", "   ") == KHONG_CO
    assert giai_thich_trang_thai("loan", None) is None
    assert dich_trang_thai("loan", "FROZEN") == "FROZEN (chưa có diễn giải)"
    assert giai_thich_trang_thai("loan", "FROZEN") is None
    assert dich_trang_thai("loan", " settled ") == "Đã tất toán"


def test_nhom_sai_la_loi_lap_trinh():
    with pytest.raises(ValueError):
        dich_trang_thai("khong_co_nhom", "ACTIVE")
    with pytest.raises(ValueError):
        giai_thich_trang_thai("khong_co_nhom", "ACTIVE")


def test_ly_do_chot_chan_dung_lai_dien_giai_ai():
    for ma, cau in DIEN_GIAI_CHOT_CHAN.items():
        if ma == "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT":
            continue
        assert dich_ly_do(ma) == cau


def test_tran_du_no_khong_bi_goi_la_luat_da_xac_minh():
    cau = dich_ly_do("TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT")
    assert "2866" not in cau
    assert "chờ đối chiếu" in cau


@pytest.mark.parametrize(
    "ma, chua",
    [
        ("AI_POLICY_REQUIRES_REVIEW", "chuyên viên thẩm định"),
        ("KYC_NOT_VALID", "eKYC"),
        ("INSUFFICIENT_REPAYMENT_CAPACITY", "Khả năng trả nợ"),
        ("CIC_CURRENT_GROUP_MISSING", "nhóm nợ hiện tại"),
        ("SELF_TRADE_PREVENTED", "chính người đặt"),
        ("AI_TIMEOUT", "tự thử lại"),
    ],
)
def test_ly_do_thuong_gap(ma, chua):
    assert chua in dich_ly_do(ma)


@pytest.mark.parametrize(
    "ma, chua",
    [
        ("PIN_LOCKED", "15 phút"),
        ("PIN_TOKEN_INVALID", "nhập lại PIN"),
        ("EKYC_REQUIRED", "eKYC"),
        ("ORDER_PRICE_TICK", "0,1%"),
        ("LISTING_INSUFFICIENT_REMAINING", "còn thiếu"),
        ("LOAN_APPLICATION_VERSION_CONFLICT", "tải lại"),
        ("CREDIT_ASSESSMENT_NOT_LATEST", "mới nhất"),
        ("CORE_LENDING_UNAVAILABLE", "30 giây"),
        ("REPAYMENT_AMOUNT_NOT_SCHEDULED_DUE", "đến hạn"),
        ("CONTRACT_EXPIRED", "hết hạn ký"),
        ("TERMS_CONFIRMATION_EXPIRED", "xác nhận điều khoản"),
        ("ALREADY_INVESTED", "Auto-Invest"),
        ("INSUFFICIENT_FUNDS", "Ví không đủ"),
        ("ORDER_REJECTED", "từ chối"),
    ],
)
def test_ma_loi_thuong_gap(ma, chua):
    assert chua in dich_ma_loi(ma)


def test_hai_ham_dung_chung_bang_tra():
    # Lịch sử hồ sơ chứa mã lỗi tích hợp; lastErrorCode có thể là mã lý do.
    assert dich_ly_do("PIN_LOCKED") == dich_ma_loi("PIN_LOCKED")
    assert dich_ma_loi("SELF_TRADE_PREVENTED") == dich_ly_do("SELF_TRADE_PREVENTED")


def test_bang_ly_do_va_ma_loi_khong_trung_khoa():
    assert set(glossary._LY_DO) & set(glossary._MA_LOI) == set()


def test_ma_tien_to_dong():
    assert "lịch trả nợ cuối" in dich_ly_do("FINERACT_FINAL_SCHEDULE_FINERACT_TIMEOUT")
    assert "Fineract" in dich_ma_loi("FINERACT_CIRCUIT_OPEN")
    assert "SmartCA" in dich_ma_loi("SMARTCA_SIGN_REJECTED")


def test_ma_loi_la_va_thieu():
    assert dich_ma_loi("NEW_BACKEND_CODE") == "NEW_BACKEND_CODE (chưa có diễn giải)"
    assert dich_ma_loi(None) == KHONG_CO
    assert dich_ly_do("") == KHONG_CO
    assert dich_ma_loi("pin_locked") == dich_ma_loi("PIN_LOCKED")


def test_danh_sach_ly_do():
    assert dich_danh_sach_ly_do(None) == []
    assert dich_danh_sach_ly_do(" , ") == []
    ket_qua = dich_danh_sach_ly_do(
        "CIC_CURRENT_BAD_DEBT, TERM_EXCEEDS_LEGAL_LIMIT,CIC_CURRENT_BAD_DEBT"
    )
    assert ket_qua == [
        DIEN_GIAI_CHOT_CHAN["CIC_CURRENT_BAD_DEBT"],
        DIEN_GIAI_CHOT_CHAN["TERM_EXCEEDS_LEGAL_LIMIT"],
    ]


def test_can_cu_ly_do():
    assert can_cu_ly_do("TERM_EXCEEDS_LEGAL_LIMIT") == ("LAW-P2P-2025:Điều 11 khoản 1 điểm c",)
    assert can_cu_ly_do("INTEREST_RATE_EXCEEDS_LEGAL_LIMIT") == ("LAW-CIVIL-2015:Điều 468",)
    assert can_cu_ly_do("TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT") == ()
    assert can_cu_ly_do("CIC_CURRENT_BAD_DEBT") == ()
    assert can_cu_ly_do(None) == ()


@pytest.mark.parametrize(
    "dpd, nhom",
    [
        (None, None),
        (-3, 1),
        (0, 1),
        (9, 1),
        (10, 2),
        (90, 2),
        (91, 3),
        (180, 3),
        (181, 4),
        (360, 4),
        (361, 5),
        (1000, 5),
    ],
)
def test_nhom_no_tu_dpd_dung_nguong_debt_group(dpd, nhom):
    assert nhom_no_tu_dpd(dpd) == nhom


def test_ten_nhom_no_va_no_xau():
    assert ten_nhom_no(2) == "Nhóm 2 — Cần chú ý"
    assert "nợ xấu" in ten_nhom_no(3)
    assert ten_nhom_no(None) == KHONG_CO
    assert ten_nhom_no(9) == "Nhóm 9 (chưa có diễn giải)"
    assert la_no_xau(2) is False
    assert la_no_xau(3) is True
    assert la_no_xau(None) is False
