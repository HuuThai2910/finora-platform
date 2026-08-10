"""
Cấu hình huấn luyện mô hình chấm điểm tín dụng — chỉ XGBoost.

Giai đoạn so sánh mô hình đã kết thúc: bốn thuật toán (Logistic Regression, Random
Forest, XGBoost, AdaBoost) đã được đánh giá out-of-time trên cùng bộ dữ liệu và
XGBoost thắng ở mọi chỉ số xếp hạng (AUC 0,673 · KS 0,250 · Gini 0,346). Module này
vì vậy chỉ giữ lại XGBoost — không mang theo ba thuật toán còn lại làm code chết.

Về cân bằng lớp: dùng `scale_pos_weight` (trọng số lớp) chứ KHÔNG dùng SMOTE. Thử
nghiệm A/B trên chính bộ dữ liệu này cho thấy SMOTE nén xác suất dự đoán của mô hình
cây xuống gần 0 (trung vị 0,125) khiến recall rơi từ 64% xuống 0,08%, trong khi AUC
gần như không đổi — AUC chỉ đo thứ tự xếp hạng nên nó "mù" trước hiện tượng nén xác
suất này.
"""
import numpy as np
from xgboost import XGBClassifier

from app.ml.features import FEATURE_NAMES

RANDOM_STATE = 42

# Đặc trưng mà PD buộc phải KHÔNG GIẢM theo — gánh nặng trả nợ nặng hơn thì rủi ro
# không thể thấp hơn. Không có ràng buộc này, mô hình học ngược dấu: đo trên v10 tự
# do cho thấy installment tăng 2,67 lần thì PD lại GIẢM 3,9 điểm phần trăm, vì trong
# dữ liệu huấn luyện installment cao tương quan với kỳ hạn ngắn (nhóm ít vỡ nợ hơn),
# nên nó bị học thành proxy cho "kỳ hạn ngắn = an toàn" thay vì thành gánh nặng.
DAC_TRUNG_DON_DIEU_TANG = ["installment", "effective_apr"]


def rang_buoc_don_dieu() -> tuple[int, ...]:
    """Vector ràng buộc đơn điệu, khớp thứ tự cột của `FEATURE_NAMES`."""
    return tuple(1 if ten in DAC_TRUNG_DON_DIEU_TANG else 0 for ten in FEATURE_NAMES)

# Siêu tham số tìm bằng RandomizedSearchCV 40 vòng, 3-fold CV, scoring roc_auc,
# chạy trên dữ liệu THẬT (không phải dữ liệu đã SMOTE).
XGB_TUNED_PARAMS = dict(
    n_estimators=700,
    max_depth=5,
    learning_rate=0.03,
    subsample=0.7,
    colsample_bytree=0.7,
    min_child_weight=1,
    gamma=0,
    reg_alpha=1,
    reg_lambda=1,
)


def fit_xgboost(X: np.ndarray, y: np.ndarray) -> tuple[XGBClassifier, float]:
    """Huấn luyện XGBoost, tự cân bằng lớp theo tỷ lệ âm/dương của chính `y`.

    Trả về `(model, scale_pos_weight)` — giá trị trọng số được trả ra ngoài để ghi
    vào metadata, vì nó khác nhau giữa các fold (tỷ lệ vỡ nợ mỗi năm mỗi khác) và
    là thông tin cần thiết khi tái lập kết quả.
    """
    so_am = int(np.sum(y == 0))
    so_duong = int(np.sum(y == 1))
    scale_pos_weight = so_am / max(so_duong, 1)

    model = XGBClassifier(
        **XGB_TUNED_PARAMS,
        scale_pos_weight=scale_pos_weight,
        monotone_constraints=rang_buoc_don_dieu(),
        eval_metric="auc",
        random_state=RANDOM_STATE,
    )
    model.fit(X, y)
    return model, scale_pos_weight
