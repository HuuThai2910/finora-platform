"""
Bộ đặc trưng cho mô hình chấm điểm tín dụng v14.

Nguồn dữ liệu:
  - Hồ sơ người vay tự khai trên app (thu nhập, thâm niên, nhà ở, mục đích)
  - eKYC/CCCD (tuổi)
  - CIC qua cic-service: điểm CIC (150–750) + 9 trường tín dụng thô
  - Fineract: lãi suất, kỳ hạn từ sản phẩm vay

So với v13 (22 features): thêm 9 CIC raw, 3 Fineract (int_rate, term_months,
effective_apr), 2 derived (log_du_no, ty_le_du_no_thu_nhap), 11 missing indicators
mới → tổng 47.
"""
import numpy as np
import pandas as pd

from app.ml.credit.preprocessing import tinh_effective_apr

HOME_OWNERSHIP_CATS = ["RENT", "OWN", "MORTGAGE", "OTHER"]
PURPOSE_CATS = [
    "DEBT_CONSOLIDATION", "CREDIT_CARD", "HOME_IMPROVEMENT", "OTHER",
    "MAJOR_PURCHASE", "MEDICAL", "CAR", "SMALL_BUSINESS",
    "MOVING", "VACATION", "EDUCATION",
]
VERIFICATION_CATS = ["Verified", "Source Verified", "Not Verified"]

# ── 9 trường tín dụng thô từ CIC ────────────────────────────────────────────
CIC_RAW_FEATURES = [
    "so_lan_tre_han",           # Số lần trễ hạn 24 tháng gần nhất
    "thang_tu_tre_gan_nhat",    # Số tháng từ lần trễ gần nhất (-1 = chưa từng)
    "tong_du_no",               # Tổng dư nợ (VND)
    "du_no_the_tin_dung",       # Dư nợ thẻ tín dụng (VND)
    "ty_le_su_dung_the",        # Tỷ lệ sử dụng thẻ (%)
    "so_lan_tra_cuu",           # Số lần tra cứu 6 tháng gần nhất
    "so_hop_dong_dang_co",      # Số hợp đồng đang có
    "so_thang_quan_he",         # Số tháng quan hệ tín dụng
    "nhom_no_cao_nhat",         # Nhóm nợ cao nhất (1-5)
]

# ── 2 trường từ Fineract (khôi phục từ v10) ─────────────────────────────────
FINERACT_FEATURES = [
    "int_rate",                 # Lãi suất danh nghĩa (%/năm)
    "term_months",              # Kỳ hạn vay (tháng)
]

COLUMNS_WITH_MISSING = [
    # Hồ sơ tự khai + CIC score (5 cũ)
    "person_age",
    "emp_length_years",
    "dti",
    "installment",
    "cic_score",
    # CIC raw — tất cả 9 đều NaN khi CIC fail
    *CIC_RAW_FEATURES,
    # Fineract — optional trong request
    *FINERACT_FEATURES,
]
MISSING_INDICATORS = [f"{c}_missing" for c in COLUMNS_WITH_MISSING]
AGE_BINS = ["age_under_25", "age_25_to_39", "age_40_to_59", "age_over_60"]

NUMERIC_FEATURES = [
    # Character — nhân thân
    "person_age",             # CCCD qua eKYC
    "emp_length_years",       # Hợp đồng lao động / tự khai
    # Capacity — khả năng trả nợ
    "annual_inc",             # Tự khai + sao kê lương
    # Conditions — điều kiện khoản vay
    "loan_amnt",              # Form nộp hồ sơ
    "dti",                    # Tỷ lệ nợ/thu nhập
    "installment",            # Số tiền phải trả hàng tháng
    # CIC — điểm tổng hợp
    "cic_score",              # Điểm tín dụng CIC (150-750)
    # CIC — dữ liệu thô
    *CIC_RAW_FEATURES,
    # Fineract — thông tin sản phẩm vay
    *FINERACT_FEATURES,
    # Đặc trưng dẫn xuất
    "log_income",             # log1p(annual_inc)
    "loan_to_income",         # clip(loan_amnt / annual_inc, 0, 5)
    "effective_apr",          # Lãi suất thực (%/năm) — bisection IRR
    "log_du_no",              # log1p(tong_du_no)
    "ty_le_du_no_thu_nhap",   # clip(tong_du_no / annual_inc, 0, 10)
]

TARGET_ENCODED_FEATURES = [
    "home_ownership_encoded",
    "purpose_cat_encoded",
    "verification_status_encoded",
    "interest_method_encoded",
]

# Nguồn sự thật duy nhất cho danh sách cột target-encoded, dùng chung giữa
# `encode_features()` và `scripts/train_credit_model.py`.
TARGET_COLS = ["home_ownership", "purpose_cat", "verification_status", "interest_method"]

FEATURE_NAMES = NUMERIC_FEATURES + TARGET_ENCODED_FEATURES + MISSING_INDICATORS + AGE_BINS


def tinh_target_encodings(
    df: pd.DataFrame,
    target_cols: list[str],
    target_col: str = "loan_status",
    m: float = 10.0
) -> tuple[dict[str, dict[str, float]], float]:
    """Tính toán bản đồ ánh xạ Target Encoding với Smoothing cho các cột phân loại."""
    global_mean = float(df[target_col].mean())
    encodings = {}

    for col in target_cols:
        col_enc = {}
        # Tính n_i và S_i
        stats = df.groupby(col)[target_col].agg(["count", "mean"])
        for val, row in stats.iterrows():
            n_i = row["count"]
            S_i = row["mean"]
            encoded_val = (n_i * S_i + m * global_mean) / (n_i + m)
            col_enc[str(val)] = float(encoded_val)
        encodings[col] = col_enc

    return encodings, global_mean


def encode_features(
    df: pd.DataFrame,
    target_encodings: dict[str, dict[str, float]] | None = None,
    global_mean: float | None = None
) -> pd.DataFrame:
    """Mã hóa và tạo đặc trưng mới từ DataFrame đã làm sạch.

    Tính thêm 3 đặc trưng dẫn xuất so với v13: effective_apr, log_du_no,
    ty_le_du_no_thu_nhap. Các đặc trưng dẫn xuất PHẢI tính SAU khi điền
    median — nếu tính trước thì giá trị thiếu sẽ truyền lên cột dẫn xuất
    mà không bị chặn.
    """
    df = df.copy()

    target_cols = TARGET_COLS

    if target_encodings is not None and global_mean is not None:
        # Nếu đã có sẵn mapping (lúc chạy thật hoặc validate)
        for col in target_cols:
            mapping = target_encodings.get(col, {})
            df[f"{col}_encoded"] = df[col].astype(str).map(mapping).fillna(global_mean)
    else:
        # Nếu chưa có mapping (đang huấn luyện)
        if "loan_status" in df.columns:
            encs, g_mean = tinh_target_encodings(df, target_cols, "loan_status", m=10.0)
            for col in target_cols:
                mapping = encs.get(col, {})
                df[f"{col}_encoded"] = df[col].astype(str).map(mapping).fillna(g_mean)
        else:
            # Fallback nếu không có nhãn
            g_mean = 0.15
            for col in target_cols:
                df[f"{col}_encoded"] = g_mean

    # Binning tuổi thành các nhóm (One-hot)
    df["age_under_25"] = (df["person_age"] < 25).astype(int)
    df["age_25_to_39"] = ((df["person_age"] >= 25) & (df["person_age"] < 40)).astype(int)
    df["age_40_to_59"] = ((df["person_age"] >= 40) & (df["person_age"] < 60)).astype(int)
    df["age_over_60"] = (df["person_age"] >= 60).astype(int)

    # Dẫn xuất: log thu nhập
    df["log_income"] = np.log1p(df["annual_inc"])

    # Dẫn xuất: khoản vay / thu nhập năm
    df["loan_to_income"] = df["loan_amnt"] / df["annual_inc"].replace(0, np.nan)
    df["loan_to_income"] = df["loan_to_income"].fillna(0).clip(upper=5)

    # Dẫn xuất: lãi suất thực (MỚI v14)
    df["effective_apr"] = tinh_effective_apr(
        df["installment"], df["loan_amnt"], df["term_months"]
    )

    # Dẫn xuất: log dư nợ (MỚI v14)
    df["log_du_no"] = np.log1p(df["tong_du_no"])

    # Dẫn xuất: tỷ lệ dư nợ / thu nhập (MỚI v14)
    df["ty_le_du_no_thu_nhap"] = df["tong_du_no"] / df["annual_inc"].replace(0, np.nan)
    df["ty_le_du_no_thu_nhap"] = df["ty_le_du_no_thu_nhap"].fillna(0).clip(upper=10)

    return df
