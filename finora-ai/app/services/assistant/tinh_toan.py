"""Đọc số tiền, kỳ hạn, thu nhập trong câu hỏi tính khoản vay để gọi công cụ tính mà không cần LLM.

Chạy thật ngày 16/10/2026 (CHAT-004): "Vay 30 triệu trong 12 tháng thì mỗi tháng trả bao nhiêu?" đi vào nhánh
câu chính sách vì kho kiến thức có đoạn "mô phỏng lịch trả" điểm cao, rồi gọi LLM một lần không kèm công cụ;
không có LLM thì người dùng chỉ nhận đoạn mô tả chung, có LLM cũng không ai gọi `mo_phong_khoan_vay`. Số tiền
và kỳ hạn đã nằm sẵn trong câu, và con số trả mỗi tháng phải do Loan tính (LAW-P2P-2025 Điều 22 khoản 3:
thông tin chính xác), nên code đọc tham số rồi gọi thẳng công cụ tính.

Bộ đọc cố ý chặt: thiếu kỳ hạn, có hai số tiền vay, hay câu đang hỏi về hồ sơ/khoản vay đã có thì trả None
để câu đi tiếp các nhánh cũ (LLM có công cụ hỏi lại được). So khớp trên bản bỏ dấu để câu gõ không dấu
("vay 30tr 12 thang") cũng nhận ra.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any, Literal

from app.services.assistant.guard import bo_dau
from app.services.assistant.tools.base import ToolResult
from app.services.assistant.tools.borrower import (
    render_goi_y_khoan_vay,
    render_mo_phong_khoan_vay,
)

TieuChi = Literal["TONG_LAI", "TRA_THANG"]

# Câu mẫu của hai công cụ tính (không có SuggestionDef riêng vì nút gợi ý không mang được số tiền/kỳ hạn).
CAU_MAU_TINH_VAY: dict[str, Callable[[ToolResult], str]] = {
    "mo_phong_khoan_vay": render_mo_phong_khoan_vay,
    "goi_y_khoan_vay": render_goi_y_khoan_vay,
}

# Biên của tham số công cụ (MoPhongKhoanVayParams/GoiYKhoanVayParams); ngoài biên thì để nhánh khác xử lý.
SO_TIEN_TOI_THIEU = 1_000_000
SO_TIEN_TOI_DA = 10_000_000_000

_HE_SO_DON_VI = {
    "trieu": 10**6, "tr": 10**6, "cu": 10**6,  # "củ" là cách nói "triệu" quen thuộc
    "ty": 10**9, "ti": 10**9,
    "nghin": 10**3, "ngan": 10**3, "k": 10**3,
    "dong": 1, "d": 1, "vnd": 1,
}
# Số kèm đơn vị tùy chọn trên bản bỏ dấu. "30.000.000", "30,000,000", "1,5", "30tr", "1.5 ty".
_SO_TIEN = re.compile(
    r"(?<![\w.,/#-])(?P<so>\d{1,3}(?:[.,]\d{3})+|\d+(?:[.,]\d+)?)\s*"
    r"(?P<don_vi>trieu|tr|cu|ty|ti|nghin|ngan|k|dong|vnd|d)?(?![\w])"
)
_KY_HAN_THANG = re.compile(r"(?<![\w.,])(\d{1,3})\s*(?:thang|ky)(?![\w])")
_KY_HAN_NAM = re.compile(r"(?<![\w.,])(\d{1,2}(?:[.,]5)?|mot|hai|ba|nua)\s*nam(?P<ruoi>\s+ruoi)?(?![\w])")
_SO_CHU = {"mot": Decimal(1), "hai": Decimal(2), "ba": Decimal(3), "nua": Decimal("0.5")}

# Số tiền đứng sau các cụm này là thu nhập / nợ khác phải trả mỗi tháng, không phải số tiền muốn vay.
_THU_NHAP = re.compile(r"\b(?:thu nhap|luong|kiem duoc|thu ve)\b")
_NO_KHAC = re.compile(r"\b(?:dang tra|dang no|no khac|tra no khac|dang gop|tra gop|khoan vay khac)\b")

# Câu phải hỏi chuyện tính/chọn gói, không chỉ tình cờ có hai con số.
_Y_DINH_TINH = re.compile(
    r"\b(?:tra bao nhieu|tra moi thang|moi thang|hang thang|thang tra|gop bao nhieu|lai bao nhieu|tong lai"
    r"|phai tra|tinh thu|mo phong|uoc tinh|du tinh|lich tra|goi nao|san pham nao|phu hop|hop nhat|hop ly"
    r"|so sanh|nen chon|muon vay|can vay|dinh vay|vay duoc khong|co goi)\b"
)
# Hỏi chọn gói (so sánh nhiều gói) thay vì tính cho một gói cụ thể.
_HOI_GOI = re.compile(r"\b(?:goi nao|san pham nao|phu hop|hop nhat|hop ly|so sanh|nen chon|co goi)\b")
_TRA_THANG_THAP = re.compile(
    r"(?:moi|hang) thang (?:\w+ ){0,2}?(?:thap|it|nhe) nhat|(?:thap|it|nhe) nhat (?:\w+ ){0,2}?(?:moi|hang) thang"
    r"|tra (?:thang|gop) (?:thap|it|nhe)"
)
# Câu hỏi về hồ sơ/hợp đồng/khoản vay đã có: con số trong câu là của đối tượng đó, không phải tính thử.
_DOI_TUONG_DA_CO = re.compile(
    r"\b(?:ho so|hop dong|dang vay|da vay|dang tra no|ky toi|ky nay|tat toan|tra truoc|giai ngan|bi tu choi"
    r"|duoc duyet|con no|khoan vay (?:cua toi|cua minh|nay|do|toi dang)|la-|lc-|ln-)"
)


@dataclass(frozen=True)
class YeuCauTinhVay:
    """Tham số đọc được từ câu hỏi; `hoi_goi` = người hỏi muốn so sánh/chọn gói."""

    amount: int
    term_months: int
    monthly_income: int | None = None
    monthly_debt: int | None = None
    tieu_chi: TieuChi = "TONG_LAI"
    hoi_goi: bool = False


def _doc_so(chuoi: str, don_vi: str | None) -> int | None:
    """"30.000.000" → 30000000, "1,5" + "ty" → 1500000000. Số không đơn vị dưới 100.000 không phải số tiền."""
    nhom_nghin = re.fullmatch(r"\d{1,3}(?:[.,]\d{3})+", chuoi)
    if nhom_nghin and (don_vi in (None, "dong", "d", "vnd") or chuoi.count(".") + chuoi.count(",") > 1):
        so = Decimal(re.sub(r"[.,]", "", chuoi))
    else:
        try:
            so = Decimal(chuoi.replace(",", "."))
        except InvalidOperation:
            return None
    if don_vi is None:
        return int(so) if so >= 100_000 and so == so.to_integral_value() else None
    return int(so * _HE_SO_DON_VI[don_vi])


def _ky_han(cau: str) -> int | None:
    """Kỳ hạn tính bằng tháng; có hai kỳ hạn khác nhau thì không đoán."""
    thay: set[int] = {int(m.group(1)) for m in _KY_HAN_THANG.finditer(cau)}
    for m in _KY_HAN_NAM.finditer(cau):
        so = _SO_CHU.get(m.group(1)) or Decimal(m.group(1).replace(",", "."))
        thang = so * 12 + (6 if m.group("ruoi") else 0)
        if thang == thang.to_integral_value() and thang > 0:
            thay.add(int(thang))
    return next(iter(thay)) if len(thay) == 1 else None


def doc_yeu_cau_tinh_vay(text: str) -> YeuCauTinhVay | None:
    """Số tiền vay + kỳ hạn (bắt buộc), thu nhập và nợ khác mỗi tháng (tùy) trong câu tính khoản vay."""
    cau = " " + " ".join(bo_dau(text).split()) + " "
    if not _Y_DINH_TINH.search(cau) or _DOI_TUONG_DA_CO.search(cau):
        return None
    ky_han = _ky_han(cau)
    if ky_han is None:
        return None

    so_vay: set[int] = set()
    thu_nhap: int | None = None
    no_khac: int | None = None
    het_doan_truoc = 0
    for khop in _SO_TIEN.finditer(cau):
        gia_tri = _doc_so(khop.group("so"), khop.group("don_vi"))
        if gia_tri is None:
            continue
        # Chỉ nhìn đoạn chữ từ số trước tới số này: "thu nhập 15 triệu, cần vay 50 triệu" có hai đoạn.
        doan = cau[het_doan_truoc: khop.start()]
        het_doan_truoc = khop.end()
        if _THU_NHAP.search(doan):
            thu_nhap = gia_tri
        elif _NO_KHAC.search(doan):
            no_khac = gia_tri
        else:
            so_vay.add(gia_tri)
    if len(so_vay) != 1:
        return None
    so_tien = next(iter(so_vay))
    if not SO_TIEN_TOI_THIEU <= so_tien <= SO_TIEN_TOI_DA:
        return None
    return YeuCauTinhVay(
        amount=so_tien,
        term_months=ky_han,
        monthly_income=thu_nhap,
        monthly_debt=no_khac,
        tieu_chi="TRA_THANG" if _TRA_THANG_THAP.search(cau) else "TONG_LAI",
        hoi_goi=bool(_HOI_GOI.search(cau)) or thu_nhap is not None,
    )


def ke_hoach_tinh_vay(yeu_cau: YeuCauTinhVay, context_params: Mapping[str, str]) -> tuple[str, dict[str, Any]]:
    """Công cụ và tham số (camelCase như LLM thấy) cho một yêu cầu tính.

    Đang mở một sản phẩm (`productId` trên màn) và không hỏi so sánh/không có thu nhập → tính đúng sản phẩm
    đó (`mo_phong_khoan_vay`). Còn lại so sánh tối đa 3 gói khớp số tiền/kỳ hạn (`goi_y_khoan_vay`): câu
    không nêu gói nào thì chọn hộ một gói là đoán.
    """
    ma_goi = context_params.get("productId")
    if ma_goi and ma_goi.isdigit() and not yeu_cau.hoi_goi:
        return "mo_phong_khoan_vay", {
            "productId": int(ma_goi), "amount": yeu_cau.amount, "termMonths": yeu_cau.term_months,
        }
    tham_so: dict[str, Any] = {
        "amount": yeu_cau.amount, "termMonths": yeu_cau.term_months, "tieuChi": yeu_cau.tieu_chi,
    }
    if yeu_cau.monthly_income is not None:
        tham_so["monthlyIncome"] = yeu_cau.monthly_income
    if yeu_cau.monthly_debt is not None:
        tham_so["monthlyDebt"] = yeu_cau.monthly_debt
    return "goi_y_khoan_vay", tham_so
