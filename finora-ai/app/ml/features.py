"""
Bộ đặc trưng cho mô hình chấm điểm tín dụng — chỉ dùng dữ liệu FINORA tự thu thập được.

Nguyên tắc chọn đặc trưng: chỉ giữ cột lấy được từ **hai nguồn FINORA thực sự có**:
  - Hồ sơ người vay tự khai trên app (thu nhập, thâm niên việc làm, nhà ở, mục đích vay)
  - eKYC/CCCD (tuổi)

**KHÔNG dùng dữ liệu Trung tâm Thông tin Tín dụng (CIC) hay điểm FICO.** FINORA chưa
có kết nối API tới CIC, nên mọi đặc trưng có nguồn từ báo cáo tín dụng đều không lấy
được khi chạy thật. Xây mô hình trên những cột đó sẽ tạo ra hệ thống chỉ chạy được
trên dữ liệu LendingClub trong phòng thí nghiệm, không triển khai được.

**Vấn đề còn tồn đọng — `int_rate` và `installment` là cột nội sinh.** Hai cột này
đang nằm trong `NUMERIC_FEATURES` và được mô hình sử dụng, nhưng FINORA tự quyết lãi
suất TỪ điểm rủi ro, nên lấy chúng làm đầu vào để tính lại điểm là lập luận vòng tròn.
Cần xử lý khi huấn luyện lại: hoặc bỏ khỏi bộ đặc trưng, hoặc thay bằng đại lượng bất
biến theo phương pháp tính lãi (`effective_apr`, tỷ lệ trả nợ trên thu nhập).

**Hạn chế phải nêu trong khóa luận:** lịch sử tín dụng là nhóm tín hiệu mạnh nhất
trong chấm điểm tín dụng. Bỏ toàn bộ nhóm này làm sức phân biệt của mô hình giảm rõ
rệt — xem chỉ số thực đo trong `models/model_v<n>.json`. Đây là đánh đổi có chủ ý:
một mô hình yếu hơn nhưng **chạy được bằng dữ liệu FINORA thật sự có**, thay vì một
mô hình mạnh hơn nhưng không triển khai được. Khi FINORA kết nối được CIC (Quyết định
2970/QĐ-NHNN quy định nghĩa vụ này), cần huấn luyện lại với nhóm đặc trưng tín dụng.
"""
import numpy as np
import pandas as pd

from app.ml.preprocessing import tinh_effective_apr

HOME_OWNERSHIP_CATS = ["RENT", "OWN", "MORTGAGE", "OTHER"]
PURPOSE_CATS = [
    "DEBT_CONSOLIDATION", "CREDIT_CARD", "HOME_IMPROVEMENT", "OTHER",
    "MAJOR_PURCHASE", "MEDICAL", "CAR", "SMALL_BUSINESS",
    "MOVING", "VACATION", "EDUCATION",
]
VERIFICATION_CATS = ["Verified", "Source Verified", "Not Verified"]

COLUMNS_WITH_MISSING = [
    "person_age",
    "emp_length_years",
    "dti",
    "delinq_2yrs",
    "pub_rec",
    "int_rate",
    "installment",
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
    # Các đặc trưng tài chính bổ sung từ CIC/FICO
    "dti",                    # Tỷ lệ nợ/thu nhập
    "term_months",            # Kỳ hạn vay (tháng)
    "delinq_2yrs",            # Số lần trễ hạn trong 2 năm
    "pub_rec",                # Hồ sơ công khai xấu
    "int_rate",               # Lãi suất danh nghĩa của gói vay (%)
    "installment",            # Số tiền phải trả hàng tháng
    # Đặc trưng dẫn xuất
    "log_income",             # log(annual_inc) — nén đuôi phân phối lệch phải
    "loan_to_income",         # loan_amnt / annual_inc
    "effective_apr",          # Chi phí thật — so sánh được giữa 3 phương pháp tính lãi
]

TARGET_ENCODED_FEATURES = [
    "home_ownership_encoded",
    "purpose_cat_encoded",
    "verification_status_encoded",
    "interest_method_encoded",
]

# Nguồn sự thật duy nhất cho danh sách cột target-encoded, dùng chung giữa
# `encode_features()` và `scripts/train_final_model.py`.
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
    """Mã hóa và tạo đặc trưng mới từ DataFrame đã làm sạch."""
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

    # Engineered: log thu nhập
    df["log_income"] = np.log1p(df["annual_inc"])

    # Engineered: khoản vay / thu nhập năm
    df["loan_to_income"] = df["loan_amnt"] / df["annual_inc"].replace(0, np.nan)
    df["loan_to_income"] = df["loan_to_income"].fillna(0).clip(upper=5)

    # Engineered: chi phí thật của dòng tiền, không phụ thuộc cách gọi tên lãi suất
    df["effective_apr"] = tinh_effective_apr(
        df["installment"], df["loan_amnt"], df["term_months"]
    )

    return df
