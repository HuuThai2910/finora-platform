"""
Cấu hình huấn luyện mô hình phát hiện gian lận giao dịch ví — XGBoost.

Tách riêng khỏi `app/ml/credit/training.py` theo nguyên tắc bounded context: cấu hình
tín dụng và cấu hình gian lận không dùng chung siêu tham số, không dùng chung
ràng buộc đơn điệu, và sẽ tiến hóa độc lập.

Vì sao KHÔNG dùng lại `XGB_TUNED_PARAMS` của mô hình tín dụng: bộ đó được tìm
bằng RandomizedSearchCV trên dữ liệu tín dụng với tỷ lệ dương 14,8% và tín hiệu
yếu (AUC trần ~0,70), nên `max_depth=5` và `learning_rate=0,03` là lựa chọn chống
quá khớp. Gian lận giao dịch ngược lại: tỷ lệ dương 0,3% nhưng tín hiệu mạnh và
mang tính tương tác (số tiền lớn × giờ đêm × tài khoản nhận lạ), nên cần cây sâu
hơn để bắt tương tác và learning rate cao hơn để hội tụ trong ít vòng.
"""

import numpy as np
from xgboost import XGBClassifier

RANDOM_STATE = 42

XGB_FRAUD_PARAMS = {
    "n_estimators": 400,
    "max_depth": 6,
    "learning_rate": 0.05,
    "subsample": 0.8,
    "colsample_bytree": 0.8,
    "min_child_weight": 1,
    "gamma": 0,
    "reg_alpha": 0,
    "reg_lambda": 1,
}


def fit_xgboost_gian_lan(X: np.ndarray, y: np.ndarray) -> tuple[XGBClassifier, float]:
    """Huấn luyện XGBoost, tự cân bằng lớp theo tỷ lệ âm/dương của chính `y`.

    Dùng `scale_pos_weight` chứ không lấy mẫu lại (over/undersampling). Lý do
    giống hệt mô hình tín dụng — lấy mẫu lại làm méo phân phối xác suất đầu ra —
    nhưng ở đây hệ quả nặng hơn nhiều: với tỷ lệ dương phần nghìn, undersampling
    lớp âm sẽ vứt bỏ hàng triệu giao dịch hợp lệ, tức vứt bỏ chính thông tin định
    nghĩa "thế nào là bình thường".

    Trả về `(model, scale_pos_weight)` để giá trị trọng số được ghi vào gói model.
    """
    so_am = int((y == 0).sum())
    so_duong = int((y == 1).sum())
    if so_duong == 0:
        raise ValueError("Tập huấn luyện không có giao dịch gian lận nào.")

    scale_pos_weight = so_am / so_duong
    model = XGBClassifier(
        **XGB_FRAUD_PARAMS,
        scale_pos_weight=scale_pos_weight,
        eval_metric="aucpr",
        random_state=RANDOM_STATE,
        n_jobs=-1,
    )
    model.fit(X, y)
    return model, scale_pos_weight
