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

NUMERIC_FEATURES = [
    # Character — nhân thân
    "person_age",             # CCCD qua eKYC
    "emp_length_years",       # Hợp đồng lao động / tự khai
    # Capacity — khả năng trả nợ
    "annual_inc",             # Tự khai + sao kê lương
    # Conditions — điều kiện khoản vay
    "loan_amnt",              # Form nộp hồ sơ
    # Đặc trưng dẫn xuất
    "log_income",             # log(annual_inc) — nén đuôi phân phối lệch phải
    "loan_to_income",         # loan_amnt / annual_inc
]

ONEHOT_FEATURES = (
    [f"home_{cat}" for cat in HOME_OWNERSHIP_CATS]
    + [f"purpose_{cat}" for cat in PURPOSE_CATS]
)

FEATURE_NAMES = NUMERIC_FEATURES + ONEHOT_FEATURES


def encode_features(df: pd.DataFrame) -> pd.DataFrame:
    """Mã hóa và tạo đặc trưng mới từ DataFrame đã làm sạch."""
    df = df.copy()

    # One-hot: nhà ở
    for cat in HOME_OWNERSHIP_CATS:
        df[f"home_{cat}"] = (df["home_ownership"] == cat).astype(int)

    # One-hot: mục đích vay
    for cat in PURPOSE_CATS:
        df[f"purpose_{cat}"] = (df["purpose_cat"] == cat).astype(int)

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
