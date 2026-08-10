"""
Chuẩn hóa dữ liệu thô về schema chung.

Các hàm ở đây được dùng ở CẢ HAI nơi — lúc huấn luyện (`scripts/train_final_model.py`)
và lúc chấm điểm hồ sơ thật (`app/ml/predictor.py`). Đây là điều bắt buộc: nếu hai
bên tự chuẩn hóa theo cách riêng thì cùng một hồ sơ sẽ cho hai kết quả khác nhau.

Module không còn hàm dựng cột từ báo cáo tín dụng (`fico_score`, `credit_hist_years`,
`revol_util`...) vì FINORA không có kết nối API tới CIC — xem `app/ml/features.py`.
"""
import numpy as np
import pandas as pd

PURPOSE_MAP = {
    "debt_consolidation": "DEBT_CONSOLIDATION",
    "credit_card": "CREDIT_CARD",
    "home_improvement": "HOME_IMPROVEMENT",
    "other": "OTHER",
    "major_purchase": "MAJOR_PURCHASE",
    "medical": "MEDICAL",
    "car": "CAR",
    "small_business": "SMALL_BUSINESS",
    "moving": "MOVING",
    "vacation": "VACATION",
    "house": "HOME_IMPROVEMENT",
    "renewable_energy": "SMALL_BUSINESS",
    "wedding": "OTHER",
    "educational": "EDUCATION",
    "education": "EDUCATION",
}

HOME_OWNERSHIP_MAP = {
    "MORTGAGE": "MORTGAGE",
    "RENT": "RENT",
    "OWN": "OWN",
    "OTHER": "OTHER",
    "ANY": "OTHER",
    "NONE": "OTHER",
}


PHUONG_PHAP_TINH_LAI = ["FLAT", "DECLINING_BALANCE", "DECLINING_BALANCE_RECALC"]
PHUONG_PHAP_MAC_DINH = "DECLINING_BALANCE"


def tinh_installment(goc, lai_suat_nam, so_thang, phuong_phap):
    """Số tiền trả hàng tháng theo phương pháp tính lãi.

    FLAT tính lãi trên toàn bộ gốc ban đầu suốt kỳ vay nên trả nhiều hơn.
    DECLINING_BALANCE tính trên dư nợ còn lại. DECLINING_BALANCE_RECALC tại thời
    điểm giải ngân có lịch trả trùng khít DECLINING_BALANCE — khác biệt chỉ phát
    sinh khi khách trả trước hạn, và khi đó lãi chỉ giảm.
    """
    goc = np.asarray(goc, dtype=float)
    r = np.asarray(lai_suat_nam, dtype=float) / 100.0 / 12.0
    n = np.asarray(so_thang, dtype=float)

    giam_dan = goc * r * (1 + r) ** n / ((1 + r) ** n - 1)
    co_dinh = goc * (1 + np.asarray(lai_suat_nam, dtype=float) / 100.0 * n / 12.0) / n
    return np.where(np.asarray(phuong_phap) == "FLAT", co_dinh, giam_dan)


def tinh_effective_apr(installment, goc, so_thang, so_vong: int = 60):
    """Lãi suất thực (%/năm) của dòng tiền trả đều — giải IRR bằng bisection.

    Cần thiết vì sau khi có 3 phương pháp, `int_rate` mang hai ý nghĩa khác nhau:
    khoản vay FLAT ghi 12% thì chi phí thật khoảng 21%, còn DECLINING ghi 12% thì
    chi phí thật đúng 12%. Đây là đại lượng duy nhất so sánh được giữa các phương
    pháp, và không có công thức đóng nên phải giải lặp.
    """
    installment = np.asarray(installment, dtype=float)
    goc = np.asarray(goc, dtype=float)
    n = np.asarray(so_thang, dtype=float)

    thap = np.full(np.broadcast(installment, goc, n).shape, 1e-12)
    cao = np.full_like(thap, 0.5)
    for _ in range(so_vong):
        giua = (thap + cao) / 2.0
        thu = goc * giua * (1 + giua) ** n / ((1 + giua) ** n - 1)
        nho_hon = thu < installment
        thap = np.where(nho_hon, giua, thap)
        cao = np.where(nho_hon, cao, giua)
    return (thap + cao) / 2.0 * 12.0 * 100.0


def _parse_emp_length(val) -> float:
    """Đọc thâm niên việc làm từ chuỗi ("10+ years", "< 1 year", "5 years")."""
    if pd.isna(val):
        return np.nan
    s = str(val).strip()
    if "10+" in s:
        return 10.0
    if "< 1" in s:
        return 0.5
    digits = "".join(c for c in s if c.isdigit())
    return float(digits) if digits else np.nan


def _parse_issue_year(issue_d: pd.Series) -> pd.Series:
    """Năm phát hành, suy ra từ `issue_d` (vd "Dec-15" → 2015)."""
    return pd.to_datetime(issue_d, format="%b-%y", errors="coerce").dt.year
