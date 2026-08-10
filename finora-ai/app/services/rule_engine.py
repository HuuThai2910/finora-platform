"""
Rule Engine — chấm điểm rủi ro bằng quy tắc tường minh, chạy song song với mô hình ML.

Đây là phần minh bạch nhất của hệ thống: ngưỡng cố định, không học từ dữ liệu, giải
trình được với người vay và với kiểm toán mà không cần công cụ nào.

Bốn yếu tố hiện tại đều tính được từ hồ sơ tự khai + eKYC:

| Yếu tố | Nhóm 5C | Nguồn dữ liệu | Thay cho yếu tố cũ |
|---|---|---|---|
| Tỷ lệ vay trên thu nhập | Capacity | Form vay + thu nhập tự khai | `dti` (CIC) |
| Thâm niên việc làm | Character | Hợp đồng lao động | Điểm FICO (CIC) |
| Tình trạng nhà ở | Capital | Tự khai | (giữ nguyên) |
| Mức thu nhập năm | Capacity | Sao kê lương | Lịch sử trễ hạn (CIC) |

Khoảng điểm AI, hạng tín dụng, hạn mức và ngưỡng duyệt được đọc từ
config/product_config.json — xem app/services/product_config.py.
"""
from collections import namedtuple

from app.services.product_config import get_approval_thresholds, get_grades, get_legal_limits, get_model_weights

XepHangTinDung = namedtuple("XepHangTinDung", ["hang", "han_muc"])

# Ngưỡng thu nhập đặt theo mức lao động phổ thông tại Việt Nam:
# 120 triệu/năm ≈ 10 triệu/tháng · 300 triệu/năm ≈ 25 triệu/tháng.
THU_NHAP_CAO = 300_000_000
THU_NHAP_TRUNG_BINH = 120_000_000


def _build_bang_xep_hang() -> list[tuple[int, XepHangTinDung]]:
    """Dựng bảng xếp hạng từ config, sắp giảm dần theo min_score."""
    legal = get_legal_limits()
    tran = legal["max_platform_limit"]
    grades = sorted(get_grades(), key=lambda g: g["min_score"], reverse=True)
    bang = []
    for g in grades:
        han_muc = g["limit"]
        if han_muc > tran:
            raise ValueError(
                f"Hạng {g['grade']}: hạn mức {han_muc:,} vượt trần "
                f"{tran:,} đồng/nền tảng của Nghị định 94/2025"
            )
        bang.append((g["min_score"], XepHangTinDung(g["grade"], han_muc)))
    return bang


def _doc_tham_nien(emp_length) -> float | None:
    """Đọc thâm niên từ chuỗi dạng LendingClub ("10+ years", "< 1 year")."""
    if emp_length is None:
        return None
    chuoi = str(emp_length).strip()
    if "10+" in chuoi:
        return 10.0
    if "< 1" in chuoi:
        return 0.5
    chu_so = "".join(c for c in chuoi if c.isdigit())
    return float(chu_so) if chu_so else None


def tinh_diem_rui_ro(features: dict) -> int:
    """Tính điểm rủi ro theo quy tắc 5C (0-100), mỗi yếu tố tối đa 25 điểm."""
    diem = 0
    thu_nhap = features.get("annual_inc") or 0

    # 1. Capacity — tỷ lệ khoản vay trên thu nhập năm (25 điểm)
    ty_le_vay = (features.get("loan_amnt") or 0) / thu_nhap if thu_nhap > 0 else 99.0
    if ty_le_vay <= 0.20:
        diem += 25
    elif ty_le_vay <= 0.50:
        diem += 15
    else:
        diem += 5

    # 2. Character — thâm niên việc làm (25 điểm)
    tham_nien = features.get("emp_length_years")
    if tham_nien is None:
        tham_nien = _doc_tham_nien(features.get("emp_length"))
    if tham_nien is None:
        diem += 5
    elif tham_nien >= 5:
        diem += 25
    elif tham_nien >= 2:
        diem += 15
    else:
        diem += 5

    # 3. Capital — tình trạng nhà ở (25 điểm)
    diem_nha = {"OWN": 25, "MORTGAGE": 20, "RENT": 10, "OTHER": 5}
    diem += diem_nha.get(features.get("home_ownership", "OTHER"), 5)

    # 4. Capacity — mức thu nhập năm tuyệt đối (25 điểm)
    if thu_nhap >= THU_NHAP_CAO:
        diem += 25
    elif thu_nhap >= THU_NHAP_TRUNG_BINH:
        diem += 15
    else:
        diem += 5

    # 5. Phạt điểm rủi ro nếu có bất hợp lý nhẹ giữa tuổi và thâm niên làm việc
    tuoi = features.get("person_age")
    if tuoi is not None and tham_nien is not None:
        if 10 <= tuoi - tham_nien < 18:
            diem = max(0, diem - 10)

    return diem


def tinh_diem_tong_hop(pd_probability: float, risk_score: int) -> float:
    """Tính điểm tổng hợp = pd_score x pd_weight + risk_score x risk_weight."""
    weights = get_model_weights()
    pd_w = weights["pd_weight"]
    risk_w = weights["risk_weight"]
    pd_score = (1 - pd_probability) * 100
    return pd_score * pd_w + risk_score * risk_w


def xep_hang(evaluation_score: float) -> XepHangTinDung:
    """Xếp hạng tín dụng A/B/C/D/E dựa trên điểm tổng hợp và config."""
    bang = _build_bang_xep_hang()
    for nguong, hang in bang:
        if evaluation_score >= nguong:
            return hang
    return bang[-1][1]


def kiem_tra_chot_chan_cung(features: dict) -> str | None:
    """Kiểm tra các quy tắc loại trừ thẳng (Knock-out Rules).

    Trả về lý do từ chối (str) nếu vi phạm, ngược lại trả về None.
    """
    legal = get_legal_limits()

    # 1. Luật trần lãi suất 20%/năm theo Điều 468 Bộ luật Dân sự 2015
    lai_suat = features.get("int_rate")
    if lai_suat is not None:
        val = float(lai_suat)
        ty_le_lai = val if val <= 1.0 else val / 100.0
        if ty_le_lai > legal["max_interest_rate"]:
            return "INTEREST_RATE_EXCEEDS_LEGAL_LIMIT"
        if ty_le_lai <= 0.0:
            return "INVALID_INTEREST_RATE"

    # 2. Luật trần kỳ hạn theo Nghị định 94/2025/NĐ-CP
    ky_han = features.get("term_months")
    if ky_han is not None:
        if ky_han > legal["max_term_months"]:
            return "TERM_EXCEEDS_LEGAL_LIMIT"

    # 3. Luật áp lực trả nợ (Installment-to-Income Ratio)
    loan_amnt = features.get("loan_amnt")
    annual_inc = features.get("annual_inc")
    if (
        loan_amnt is not None
        and annual_inc is not None
        and lai_suat is not None
        and ky_han is not None
        and annual_inc > 0
    ):
        val = float(lai_suat)
        ty_le_lai = val if val <= 1.0 else val / 100.0
        r_thang = ty_le_lai / 12.0
        n_thang = int(ky_han)

        if r_thang > 0:
            installment = (loan_amnt * r_thang * ((1 + r_thang) ** n_thang)) / (((1 + r_thang) ** n_thang) - 1)
        else:
            installment = loan_amnt / n_thang

        thu_nhap_thang = annual_inc / 12.0
        ty_le_tra_no = installment / thu_nhap_thang

        if ty_le_tra_no > 0.50:
            return "DEBT_SERVICE_RATIO_TOO_HIGH"

    # 4. Kiểm tra sự bất hợp lý quá lớn giữa tuổi và kinh nghiệm làm việc
    tuoi = features.get("person_age")
    emp_length_raw = features.get("emp_length")

    tham_nien = features.get("emp_length_years")
    if tham_nien is None and emp_length_raw is not None:
        tham_nien = _doc_tham_nien(emp_length_raw)

    if tuoi is not None and tham_nien is not None:
        if tuoi - tham_nien < 10:
            return "AGE_AND_EXPERIENCE_INCONSISTENCY"

    return None


def quyet_dinh(evaluation_score: float, chot_chan_ly_do: str | None = None) -> str:
    """Quyết định tự động: REJECTED / PENDING_REVIEW / APPROVED.

    Ngưỡng đọc từ config/product_config.json:
      - evaluation_score >= auto_approve → APPROVED
      - evaluation_score <  auto_reject  → REJECTED
      - Còn lại                          → PENDING_REVIEW
    """
    if chot_chan_ly_do is not None:
        return "REJECTED"
    thresholds = get_approval_thresholds()
    if evaluation_score >= thresholds["auto_approve"]:
        return "APPROVED"
    if evaluation_score < thresholds["auto_reject"]:
        return "REJECTED"
    return "PENDING_REVIEW"
