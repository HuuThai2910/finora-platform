import numpy as np
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    f1_score,
    fbeta_score,
    precision_recall_curve,
    precision_score,
    recall_score,
    roc_auc_score,
)


def compute_ks_statistic(y_true: np.ndarray, y_proba: np.ndarray) -> float:
    """Tính chỉ số Kolmogorov-Smirnov — đo mức phân tách 2 phân phối.

    Bản trước duyệt từng ngưỡng bằng vòng lặp Python, mỗi vòng quét lại toàn bộ hai
    mảng, nên chi phí tăng theo bậc hai: đo được 5,0s với 50.000 dòng nhưng 40,4s
    với 200.000 dòng, và ngoại suy lên 552.504 dòng của tập validate gian lận là
    khoảng 5 phút cho MỘT lần gọi.

    Bản này thay vòng lặp bằng `searchsorted` trên hai mảng đã sắp xếp. Đồng nhất về
    mặt toán học chứ không phải xấp xỉ: `mean(pos >= t)` đúng bằng
    `1 - searchsorted(pos, t, "left") / len(pos)` khi `pos` đã sắp tăng dần.
    """
    pos_proba = np.sort(y_proba[y_true == 1])
    neg_proba = np.sort(y_proba[y_true == 0])
    if len(pos_proba) == 0 or len(neg_proba) == 0:
        return 0.0

    tat_ca_nguong = np.unique(np.concatenate([pos_proba, neg_proba]))
    tpr = 1.0 - np.searchsorted(pos_proba, tat_ca_nguong, side="left") / len(pos_proba)
    fpr = 1.0 - np.searchsorted(neg_proba, tat_ca_nguong, side="left") / len(neg_proba)
    return float(np.max(np.abs(tpr - fpr)))


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


def danh_gia_theo_nguong(
    y_true: np.ndarray, y_proba: np.ndarray, nguong: float
) -> dict:
    """Đánh giá tại một ngưỡng cắt cho trước, kèm PR-AUC.

    Khác `evaluate_model` ở hai điểm, đều xuất phát từ dữ liệu cực kỳ mất cân bằng
    (gian lận giao dịch chỉ chiếm phần nghìn, so với 14,8% vỡ nợ của bài toán tín
    dụng):

    1. Ngưỡng là tham số chứ không cố định 0,5. Với tỷ lệ dương hiếm, ngưỡng 0,5
       gần như không bao giờ là điểm vận hành hợp lý; ngưỡng thật phải chọn trên
       dữ liệu hiệu chỉnh rồi lưu vào gói model.
    2. Có `auc_pr` (average precision). ROC-AUC bị thổi phồng khi lớp âm áp đảo —
       thêm hàng chục nghìn âm tính đúng gần như không đổi FPR. Đường
       precision-recall không có nhược điểm đó nên `auc_pr` mới là chỉ số xếp hạng
       đáng tin ở đây; `auc_pr` của mô hình ngây thơ đúng bằng tỷ lệ lớp dương.

    `accuracy` vẫn được trả kèm `accuracy_baseline` và `chenh_so_voi_baseline` —
    cùng quy ước với gói model tín dụng, để hai bài toán đọc được trên cùng thước.
    """
    y_pred = (y_proba >= nguong).astype(int)
    auc = roc_auc_score(y_true, y_proba)
    ty_le_duong = float(np.mean(y_true))
    ty_le_lop_da_so = max(ty_le_duong, 1 - ty_le_duong)
    accuracy = accuracy_score(y_true, y_pred)

    return {
        "nguong_quyet_dinh": float(nguong),
        "auc_roc": float(auc),
        "gini": float(2 * auc - 1),
        "auc_pr": float(average_precision_score(y_true, y_proba)),
        "auc_pr_baseline": ty_le_duong,
        "ks_statistic": float(compute_ks_statistic(y_true, y_proba)),
        "precision": float(
            precision_score(y_true, y_pred, pos_label=1, zero_division=0)
        ),
        "recall": float(recall_score(y_true, y_pred, pos_label=1, zero_division=0)),
        "f1": float(f1_score(y_true, y_pred, pos_label=1, zero_division=0)),
        "accuracy": float(accuracy),
        "accuracy_baseline": float(ty_le_lop_da_so),
        "chenh_so_voi_baseline": float(accuracy - ty_le_lop_da_so),
        "ty_le_gian_lan": ty_le_duong,
    }


def chon_nguong_toi_uu_fbeta(
    y_true: np.ndarray, y_proba: np.ndarray, beta: float = 1.0
) -> float:
    """Chọn ngưỡng cắt tối đa hóa F-beta.

    `beta` quyết định recall được coi trọng gấp mấy lần precision:

    - `beta = 1` — cân bằng. Hợp khi hai loại lỗi tốn kém ngang nhau.
    - `beta = 2` — recall quan trọng gấp đôi. Hợp với chống gian lận: bỏ lọt một
      giao dịch gian lận là **mất tiền thật và phải bồi thường**, còn gắn cờ nhầm
      chỉ khiến khách nhập thêm một bước xác thực. Hai loại lỗi đó không cùng hạng
      cân, nên tối ưu F1 là ngầm định sai lệch so với nghiệp vụ.

    MUST gọi trên dữ liệu **hiệu chỉnh** tách khỏi tập validate. Chọn ngưỡng ngay
    trên tập validate rồi báo cáo chỉ số của chính tập đó là tự chấm điểm bài thi
    của mình: con số đẹp lên mà không phản ánh hiệu năng lúc chạy thật.
    """
    nguong_ung_vien = np.unique(np.quantile(y_proba, np.linspace(0.90, 0.99999, 400)))
    tot_nhat, diem_tot_nhat = 0.5, -1.0
    for nguong in nguong_ung_vien:
        diem = fbeta_score(
            y_true,
            (y_proba >= nguong).astype(int),
            beta=beta,
            pos_label=1,
            zero_division=0,
        )
        if diem > diem_tot_nhat:
            tot_nhat, diem_tot_nhat = float(nguong), diem
    return tot_nhat


def nguong_dat_recall(
    y_true: np.ndarray, y_proba: np.ndarray, muc_recall: float
) -> float:
    """Ngưỡng cao nhất còn đạt được `muc_recall` trên chính tập truyền vào.

    Dùng để dựng bảng đánh đổi recall–precision cho `finora-payment` chọn điểm vận
    hành. Cũng MUST gọi trên tập hiệu chỉnh, vì lý do y hệt hàm trên.

    Trả về 0,0 khi không ngưỡng nào đạt được mức recall yêu cầu — nghĩa là "gắn cờ
    tất cả", và người gọi cần thấy điều đó thay vì nhận một ngưỡng trông hợp lệ.
    """
    _, rec, thr = precision_recall_curve(y_true, y_proba)
    # `precision_recall_curve` trả về len(thr) == len(rec) - 1; phần tử cuối của
    # rec là 0 và không có ngưỡng tương ứng, nên cắt bỏ trước khi tra.
    dat = np.where(rec[:-1] >= muc_recall)[0]
    return float(thr[dat[-1]]) if len(dat) else 0.0
