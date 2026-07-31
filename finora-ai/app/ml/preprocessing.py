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
    """Năm phát hành, suy ra từ `issue_d` (vd "Dec-2015" → 2015)."""
    return pd.to_datetime(issue_d, format="%b-%Y", errors="coerce").dt.year
