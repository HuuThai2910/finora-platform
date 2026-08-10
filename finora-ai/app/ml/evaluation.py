import numpy as np
from sklearn.metrics import (
    roc_auc_score,
    f1_score,
    recall_score,
    precision_score,
    accuracy_score,
)

def compute_ks_statistic(y_true: np.ndarray, y_proba: np.ndarray) -> float:
    """Tính chỉ số Kolmogorov-Smirnov — đo mức phân tách 2 phân phối."""
    pos_proba = np.sort(y_proba[y_true == 1])
    neg_proba = np.sort(y_proba[y_true == 0])

    tat_ca_nguong = np.sort(np.unique(np.concatenate([pos_proba, neg_proba])))
    ks = 0.0
    for nguong in tat_ca_nguong:
        tpr = np.mean(pos_proba >= nguong)
        fpr = np.mean(neg_proba >= nguong)
        ks = max(ks, abs(tpr - fpr))
    return ks


def evaluate_model(model, X_test: np.ndarray, y_test: np.ndarray) -> dict:
    """Đánh giá mô hình trên tập test, trả về dict các chỉ số.

    `accuracy` được tính kèm `accuracy_baseline` — độ chính xác của một mô hình
    ngây thơ luôn đoán lớp đa số. Hai số này phải luôn đọc cùng nhau: với dữ liệu
    tín dụng mất cân bằng, accuracy cao không nói lên điều gì nếu nó chỉ ngang
    baseline. Ví dụ tập test của đề tài có 14,89% vỡ nợ, nên đoán "ai cũng trả đủ"
    đã đạt 85,11% — bằng đúng mô hình tốt nhất. Chỉ số dùng để xếp hạng mô hình
    vẫn là AUC/KS/Gini, còn Recall cho biết mô hình bắt được bao nhiêu ca vỡ nợ.
    """
    y_proba = model.predict_proba(X_test)[:, 1]
    y_pred = (y_proba >= 0.5).astype(int)

    auc = roc_auc_score(y_test, y_proba)
    ty_le_lop_da_so = max(np.mean(y_test), 1 - np.mean(y_test))

    return {
        "auc_roc": auc,
        "gini": 2 * auc - 1,
        "f1": f1_score(y_test, y_pred, pos_label=1, zero_division=0),
        "recall": recall_score(y_test, y_pred, pos_label=1, zero_division=0),
        "precision": precision_score(y_test, y_pred, pos_label=1, zero_division=0),
        "accuracy": accuracy_score(y_test, y_pred),
        "accuracy_baseline": float(ty_le_lop_da_so),
        "ks_statistic": compute_ks_statistic(y_test, y_proba),
    }
