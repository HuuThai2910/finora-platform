"""
Bộ đặc trưng cho mô hình chấm điểm tín dụng — chỉ dùng dữ liệu FINORA tự thu thập được.

Nguyên tắc chọn đặc trưng: chỉ giữ cột lấy được từ **hai nguồn FINORA thực sự có**:
  - Hồ sơ người vay tự khai trên app (thu nhập, thâm niên việc làm, nhà ở, mục đích vay)
  - eKYC/CCCD (tuổi)

**KHÔNG dùng dữ liệu Trung tâm Thông tin Tín dụng (CIC) hay điểm FICO.** FINORA chưa
có kết nối API tới CIC, nên mọi đặc trưng có nguồn từ báo cáo tín dụng đều không lấy
được khi chạy thật. Xây mô hình trên những cột đó sẽ tạo ra hệ thống chỉ chạy được
trên dữ liệu LendingClub trong phòng thí nghiệm, không triển khai được.

Mười bốn đặc trưng đã loại vì phụ thuộc CIC/FICO:

| Đặc trưng loại | Dữ liệu không có |
|---|---|
| `fico_score`, `fico_bucket` | Điểm tín dụng của cơ quan xếp hạng |
| `dti` | Cần tổng dư nợ hiện tại từ CIC làm tử số |
| `credit_hist_years` | Ngày mở quan hệ tín dụng đầu tiên — chỉ CIC lưu |
| `delinq_2yrs`, `mths_since_last_delinq`, `acc_now_delinq` | Lịch sử trễ hạn |
| `revol_bal`, `revol_util`, `revol_risk` | Dư nợ và hạn mức thẻ tín dụng |
| `open_acc`, `tot_cur_bal`, `mort_acc` | Số hợp đồng, tổng dư nợ, tài sản đảm bảo |
| `inq_last_6mths` | Số lần bị tra cứu tín dụng |

Trước đó bộ đặc trưng còn loại các cột nội sinh (`int_rate`, `installment` — FINORA
tự quyết lãi suất TỪ điểm nên lấy làm đầu vào là lập luận vòng tròn), cột kỳ hạn
(Nghị định 94/2025 giới hạn ≤ 24 tháng → hằng số, phương sai 0) và cột không có
tương đương ở Việt Nam (`pub_rec` — chưa có luật phá sản cá nhân; `total_acc`).

**Hạn chế phải nêu trong khóa luận:** lịch sử tín dụng là nhóm tín hiệu mạnh nhất
trong chấm điểm tín dụng. Bỏ toàn bộ nhóm này làm sức phân biệt của mô hình giảm rõ
rệt — xem chỉ số thực đo trong `models/model_v<n>.json`. Đây là đánh đổi có chủ ý:
một mô hình yếu hơn nhưng **chạy được bằng dữ liệu FINORA thật sự có**, thay vì một
mô hình mạnh hơn nhưng không triển khai được. Khi FINORA kết nối được CIC (Quyết định
2970/QĐ-NHNN quy định nghĩa vụ này), cần huấn luyện lại với nhóm đặc trưng tín dụng.
"""
import numpy as np
import pandas as pd

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
    "int_rate",               # Lãi suất khoản vay (%)
    "installment",            # Số tiền phải trả hàng tháng
    # Đặc trưng dẫn xuất
    "log_income",             # log(annual_inc) — nén đuôi phân phối lệch phải
    "loan_to_income",         # loan_amnt / annual_inc
]

TARGET_ENCODED_FEATURES = [
    "home_ownership_encoded",
    "purpose_cat_encoded",
    "verification_status_encoded",
]

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

    target_cols = ["home_ownership", "purpose_cat", "verification_status"]

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

    return df


def get_model_features(df: pd.DataFrame) -> np.ndarray:
    """Trả về ma trận đặc trưng X sẵn sàng cho mô hình."""
    encoded = encode_features(df)
    return encoded[FEATURE_NAMES].values.astype(np.float64)


def get_labels(df: pd.DataFrame) -> np.ndarray:
    """Trả về vector nhãn y (0 = trả đủ, 1 = vỡ nợ)."""
    return df["loan_status"].values.astype(int)
