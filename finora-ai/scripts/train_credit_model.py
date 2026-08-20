"""
Huấn luyện mô hình XGBoost CUỐI CÙNG cho FINORA AI Service.

Khác `train_initial_model` ở giai đoạn trước: không còn so sánh bốn thuật toán và
không giữ lại tập hold-out. Giai đoạn chọn mô hình đã kết thúc, nên mô hình cuối
được huấn luyện trên **100% dữ liệu** — nhiều dữ liệu hơn, cùng cấu hình đã thắng.

Hiệu năng vẫn được đo trước khi refit, bằng hai cách bổ sung cho nhau:

  1. **Out-of-time** (3 fold) — train các năm trước, validate năm kế tiếp. Đây là
     tình huống triển khai thật: mô hình học từ quá khứ rồi chấm hồ sơ tương lai.
  2. **K-fold ngẫu nhiên** (5 fold) — trộn đều mọi năm. Chỉ số sẽ CAO HƠN cách 1 vì
     không kiểm tra khả năng tổng quát theo thời gian. Hai con số đo hai thứ khác
     nhau, không thay thế nhau.

Ba hiệu chỉnh theo bối cảnh FINORA:
  - Chỉ giữ khoản vay có kỳ hạn tối đa 24 tháng để phù hợp giới hạn hợp đồng vay
    ngang hàng theo Nghị định 94/2025.
# Chuẩn hóa các biến tiền tệ của LendingClub sang mặt bằng thu nhập
# của người lao động Việt Nam.
#
# annual_inc và loan_amnt KHÔNG được quy đổi theo tỷ giá USD/VND.
# Thay vào đó sử dụng hệ số:
#
#     Average Income VN 2025
# k = -----------------------
#     Average Earnings US 2018
#
# nhằm bảo toàn vị trí thu nhập tương đối giữa hai quốc gia.
  - **Bộ đặc trưng gồm dữ liệu FINORA tự thu thập + điểm CIC**: hồ sơ tự khai + eKYC
    + cic_score (150–750) từ cic-service. Trong dữ liệu huấn luyện, cic_score được tổng
    hợp từ fico_score của LendingClub bằng ánh xạ tuyến tính + nhiễu Gaussian, ~15% đặt
    NaN để mô hình học cách xử lý khi CIC không khả dụng. Xem `app/ml/credit/features.py`.

Đầu ra là một **gói tự chứa**: ngoài model còn có median điền thiếu, siêu tham số,
công thức đặc trưng dẫn xuất và chỉ số từng fold — đủ để chấm một hồ sơ mới mà
không cần đọc lại file này.

Cách dùng:
    cd finora-ai
    python scripts/train_credit_model.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import numpy as np
import pandas as pd
from sklearn.model_selection import StratifiedKFold

from app.ml.credit.features import (
    CIC_RAW_FEATURES,
    COLUMNS_WITH_MISSING,
    FEATURE_NAMES,
    HOME_OWNERSHIP_CATS,
    PURPOSE_CATS,
    encode_features,
)
from app.ml.credit.predictor import COT_DIEN_MEDIAN
from app.ml.credit.preprocessing import (
    PURPOSE_MAP,
    _parse_emp_length,
    _parse_issue_year,
    map_nhom_no,
    tinh_so_thang_quan_he,
)
from app.ml.credit.training import RANDOM_STATE, XGB_TUNED_PARAMS, fit_xgboost
from app.ml.shared.evaluation import evaluate_model
from app.ml.shared.model_registry import luu_mo_hinh

# ── Cấu hình ──────────────────────────────────────────────────────────────────
PHIEN_BAN = "15.0.0"

# Hệ số quy đổi thống nhất — trung bình của 2012 và 2014:
#   VN_AVG = (44_400_000 + 53_880_000) / 2 = 49_140_000
#   US_AVG = (55_300 + 57_450) / 2          = 56_375
#   k = 49_140_000 / 56_375 ≈ 871.6
HE_SO_K = 49_140_000 / 56_375

# Chỉ lấy từ 2009 trở đi — bỏ 2007-2008 (ít dữ liệu, khủng hoảng tài chính).
NAM_BAT_DAU = 2009

# Ngưỡng cắt PD để tính Recall/Precision/F1/Accuracy khi BÁO CÁO.
# Đường ra quyết định KHÔNG dùng ngưỡng này — `tinh_diem_tong_hop()` nhận PD liên tục.
NGUONG_BAO_CAO = 0.5

COT_TIEN_TE = ["annual_inc", "loan_amnt", "installment", "tot_cur_bal", "revol_bal"]

# Out-of-time: train trên các năm trước, validate năm kế tiếp.
# Nhiều fold hơn v14 (chỉ có 1 fold 2012→2014).
FOLD_OUT_OF_TIME = [
    ("2009-2012 -> 2013", 2012, 2013),
    ("2009-2013 -> 2014", 2013, 2014),
    ("2009-2014 -> 2015", 2014, 2015),
]

SO_FOLD_NGAU_NHIEN = 5

# Chỉ số của mô hình phiên bản trước trên tập 2015 — bản ĐÓ còn dùng 35 đặc trưng
# gồm cả lịch sử tín dụng từ CIC/FICO. Ghi lại để đo được **cái giá của việc bỏ CIC**,
# KHÔNG phải để làm ngưỡng nghiệm thu: hai mô hình dùng hai bộ đặc trưng khác nhau
# nên chênh lệch là kỳ vọng, không phải lỗi.
CHI_SO_THAM_CHIEU = {
    "mo_ta": (
        "Mô hình v7.0.0 (35 đặc trưng, CÓ dữ liệu CIC/FICO) đo trên tập 2015. "
        "Là mốc so sánh để lượng hóa mức mất mát khi bỏ nhóm đặc trưng tín dụng."
    ),
    "so_dac_trung": 35,
    "auc_roc": 0.6729593262106933,
    "recall": 0.6413396947470863,
    "ks_statistic": 0.2503210094965231,
}

KHOA_CHI_SO = [
    "auc_roc", "gini", "ks_statistic", "recall", "precision",
    "f1", "accuracy", "accuracy_baseline", "chenh_so_voi_baseline",
]

THU_MUC_GOC = Path(__file__).resolve().parent.parent
DATA_FILE = THU_MUC_GOC / "data" / "lc_clean.csv"
THU_MUC_MO_HINH = THU_MUC_GOC / "models" / "credit"


# ── Nạp và chuẩn hóa ──────────────────────────────────────────────────────────
def nap_va_chuan_hoa() -> pd.DataFrame:
    """Nạp lc_clean.csv, tính lại cột dẫn xuất, lọc năm ≥ 2009, chuẩn hóa VND thống nhất."""
    d = pd.read_csv(DATA_FILE, low_memory=False)
    print(f"  Nạp {len(d):,} dòng × {len(d.columns)} cột")

    # Hai cột này không được lưu trong file — tính lại bằng ĐÚNG hàm mà lúc chấm điểm
    # hồ sơ thật cũng dùng, để công thức không lệch giữa huấn luyện và triển khai.
    d["issue_year"] = _parse_issue_year(d["issue_d"])
    d["emp_length_years"] = d["emp_length"].apply(_parse_emp_length)

    # Lọc năm ≥ 2009 (bỏ 2007-2008) và kỳ hạn ≤ 24 tháng (NĐ 94/2025).
    truoc = len(d)
    d = d[d["issue_year"] >= NAM_BAT_DAU].copy()
    d = d[d["term_months"] <= 24].copy()
    print(
        f"  Lọc năm ≥ {NAM_BAT_DAU} và kỳ hạn ≤ 24 tháng: "
        f"{truoc:,} → {len(d):,} dòng"
    )

    # Chuẩn hóa tiền tệ — hệ số k thống nhất cho mọi năm
    for col in COT_TIEN_TE:
        d[col] = d[col] * HE_SO_K

    d["purpose_cat"] = d["purpose"].map(PURPOSE_MAP).fillna("OTHER")
    print(f"  Quy đổi VND động theo từng năm cho {len(COT_TIEN_TE)} cột tiền tệ")

    # ── Tạo 9 CIC features từ LendingClub ────────────────────────────────────
    # Ánh xạ cột LC → tên feature CIC, để mô hình v14 học cùng schema
    # với dữ liệu CIC thật sẽ nhận lúc triển khai.
    d["so_lan_tre_han"] = d["delinq_2yrs"]
    d["thang_tu_tre_gan_nhat"] = d["mths_since_last_delinq"].fillna(-1).astype(int)
    d["tong_du_no"] = d["tot_cur_bal"]      # đã VND-scaled
    d["du_no_the_tin_dung"] = d["revol_bal"]  # đã VND-scaled
    d["ty_le_su_dung_the"] = d["revol_util"]
    d["so_lan_tra_cuu"] = d["inq_last_6mths"]
    d["so_hop_dong_dang_co"] = d["open_acc"]
    d["so_thang_quan_he"] = tinh_so_thang_quan_he(d["earliest_cr_line"], d["issue_d"])
    d["nhom_no_cao_nhat"] = map_nhom_no(d["pub_rec"], d["acc_now_delinq"])
    print(f"  Tạo 9 CIC features từ cột LendingClub")

    # ── Tổng hợp cic_score từ fico_score ──────────────────────────────────────
    rng = np.random.default_rng(RANDOM_STATE)
    fico = d["fico_score"].values.astype(float)
    cic_raw = 150.0 + (fico - 300.0) * (600.0 / 550.0)
    cic_noisy = cic_raw + rng.normal(0, 30, size=len(cic_raw))
    cic_clipped = np.clip(cic_noisy, 150, 750)
    d["cic_score"] = cic_clipped

    # ~15% NaN — cùng mask cho cic_score VÀ 9 CIC raw features, mô phỏng CIC timeout
    mask_cic_fail = rng.random(len(d)) < 0.15
    cic_cols = ["cic_score"] + list(CIC_RAW_FEATURES)
    for col in cic_cols:
        d.loc[mask_cic_fail, col] = np.nan

    n_missing = mask_cic_fail.sum()
    print(
        f"  Tổng hợp cic_score từ fico_score + đặt NaN đồng bộ cho {len(cic_cols)} CIC features: "
        f"{len(d) - n_missing:,} hợp lệ, {n_missing:,} NaN ({n_missing / len(d) * 100:.1f}%)"
    )
    print(f"  Tỷ lệ vỡ nợ: {d['loan_status'].mean() * 100:.2f}%")

    return d.reset_index(drop=True)


# ── Điền thiếu & tạo ma trận ──────────────────────────────────────────────────
def tinh_median(df: pd.DataFrame) -> dict[str, float]:
    """Median của 16 cột gốc. Chỉ được gọi trên TẬP TRAIN của fold đang xét."""
    return {cot: float(df[cot].median()) for cot in COT_DIEN_MEDIAN}


def tao_ma_tran(
    df: pd.DataFrame,
    median: dict[str, float],
    target_encodings: dict[str, dict[str, float]] | None = None,
    global_mean: float | None = None
) -> tuple[np.ndarray, np.ndarray]:
    """Điền thiếu bằng median cho trước → tạo đặc trưng → trả (X, y)."""
    d = df.copy()

    # Tạo chỉ báo thiếu trước khi điền median
    for cot in COLUMNS_WITH_MISSING:
        d[f"{cot}_missing"] = d[cot].isna().astype(int)

    for cot, gia_tri in median.items():
        d[cot] = d[cot].fillna(gia_tri)

    d = encode_features(d, target_encodings, global_mean)
    X = d[FEATURE_NAMES].values.astype(np.float64)
    y = d["loan_status"].values.astype(int)

    if np.isnan(X).any():
        cot_loi = [t for t, c in zip(FEATURE_NAMES, np.isnan(X).any(axis=0)) if c]
        raise ValueError(f"Còn NaN sau khi điền median, các cột: {cot_loi}")

    return X, y


def do_mot_fold(train: pd.DataFrame, val: pd.DataFrame, ten: str) -> dict:
    """Đo hiệu năng một fold. Median fit CHỈ trên train — không thì val rò sang train."""
    median = tinh_median(train)

    from app.ml.credit.features import TARGET_COLS, tinh_target_encodings
    target_cols = TARGET_COLS
    target_encodings, global_mean = tinh_target_encodings(train, target_cols, "loan_status", m=10.0)

    X_train, y_train = tao_ma_tran(train, median, target_encodings, global_mean)
    X_val, y_val = tao_ma_tran(val, median, target_encodings, global_mean)

    model, scale_pos_weight = fit_xgboost(X_train, y_train)
    chi_so = evaluate_model(model, X_val, y_val)
    chi_so["chenh_so_voi_baseline"] = chi_so["accuracy"] - chi_so["accuracy_baseline"]
    chi_so.update({
        "ten": ten,
        "n_train": len(train),
        "n_val": len(val),
        "scale_pos_weight": scale_pos_weight,
    })

    print(
        f"  {ten:18s} n_train={len(train):>7,} n_val={len(val):>7,} "
        f"AUC={chi_so['auc_roc']:.4f} KS={chi_so['ks_statistic']:.4f} "
        f"Recall={chi_so['recall']:.4f} Acc={chi_so['accuracy']:.4f} "
        f"(baseline {chi_so['accuracy_baseline']:.4f})"
    )
    return chi_so


def do_out_of_time(d: pd.DataFrame) -> list[dict]:
    return [
        do_mot_fold(
            d[d.issue_year <= nam_cuoi_train],
            d[d.issue_year == nam_val],
            ten,
        )
        for ten, nam_cuoi_train, nam_val in FOLD_OUT_OF_TIME
    ]


def do_kfold_ngau_nhien(d: pd.DataFrame) -> list[dict]:
    skf = StratifiedKFold(n_splits=SO_FOLD_NGAU_NHIEN, shuffle=True, random_state=RANDOM_STATE)
    y = d["loan_status"].values.astype(int)
    return [
        do_mot_fold(d.iloc[idx_tr], d.iloc[idx_va], f"fold {i}/{SO_FOLD_NGAU_NHIEN}")
        for i, (idx_tr, idx_va) in enumerate(skf.split(np.zeros(len(d)), y), start=1)
    ]


def trung_binh(ds: list[dict]) -> dict:
    return {
        khoa: {
            "mean": float(np.mean([d[khoa] for d in ds])),
            "std": float(np.std([d[khoa] for d in ds])),
        }
        for khoa in KHOA_CHI_SO
    }


# ── Chạy chính ────────────────────────────────────────────────────────────────
def main() -> None:
    print("=" * 78)
    print("FINORA AI — Huấn luyện mô hình XGBoost cuối cùng (v" + PHIEN_BAN + ")")
    print("=" * 78)

    if not DATA_FILE.exists():
        print(f"\nLỖI: không tìm thấy {DATA_FILE}")
        print("Đặt lc_clean.csv vào finora-ai/data/ rồi chạy lại.")
        sys.exit(1)

    print(f"\n[1/5] Nạp và chuẩn hóa dữ liệu")
    d = nap_va_chuan_hoa()

    print(f"\n[2/5] Đo out-of-time ({len(FOLD_OUT_OF_TIME)} fold) — train quá khứ, val tương lai")
    oot = do_out_of_time(d)

    print(f"\n[3/5] Đo K-fold ngẫu nhiên ({SO_FOLD_NGAU_NHIEN} fold) — trộn đều mọi năm")
    kfold = do_kfold_ngau_nhien(d)

    print(f"\n[4/5] Huấn luyện lại trên 100% dữ liệu ({len(d):,} dòng)")
    median_cuoi = tinh_median(d)

    from app.ml.credit.features import TARGET_COLS, tinh_target_encodings
    target_cols = TARGET_COLS
    encodings_cuoi, global_mean_cuoi = tinh_target_encodings(d, target_cols, "loan_status", m=10.0)

    X, y = tao_ma_tran(d, median_cuoi, encodings_cuoi, global_mean_cuoi)
    model, scale_pos_weight = fit_xgboost(X, y)
    print(f"  scale_pos_weight = {scale_pos_weight:.3f}")

    tb_oot = trung_binh(oot)
    tb_kfold = trung_binh(kfold)

    # `metrics` giữ dạng phẳng để so sánh được với các phiên bản model trước đó;
    # chi tiết từng fold nằm trong `chi_so`.
    metrics_chinh = {khoa: tb_oot[khoa]["mean"] for khoa in KHOA_CHI_SO}

    thong_so_bo_sung = {
        "du_lieu_huan_luyen": {
            "nguon": "data/lc_clean.csv",
            "loc": f"issue_year >= {NAM_BAT_DAU}",
            "n_dong": int(len(d)),
            "khoang_nam": [int(d.issue_year.min()), int(d.issue_year.max())],
            "ty_le_vo_no": float(d["loan_status"].mean()),
            "don_vi_tien": "VND",
            "he_so_chuan_hoa": HE_SO_K,
        },
        "sieu_tham_so": {
            **XGB_TUNED_PARAMS,
            "scale_pos_weight": scale_pos_weight,
            "eval_metric": "auc",
            "random_state": RANDOM_STATE,
        },
        "median_dien_thieu": median_cuoi,
        "target_encodings": encodings_cuoi,
        "global_mean": global_mean_cuoi,
        "cong_thuc_dan_xuat": {
            "log_income": "log1p(annual_inc)",
            "loan_to_income": "clip(loan_amnt / annual_inc, 0, 5)",
            "effective_apr": "tinh_effective_apr(installment, loan_amnt, term_months)",
            "log_du_no": "log1p(tong_du_no)",
            "ty_le_du_no_thu_nhap": "clip(tong_du_no / annual_inc, 0, 10)",
        },
        "nguon_du_lieu": (
            "Hồ sơ người vay tự khai + eKYC/CCCD + CIC (điểm 150–750 + 9 trường thô) "
            "từ cic-service. Trong dữ liệu huấn luyện, CIC features ánh xạ từ LendingClub "
            "(delinq_2yrs→so_lan_tre_han, tot_cur_bal→tong_du_no, v.v.), cic_score tổng hợp "
            "từ fico_score bằng ánh xạ tuyến tính + nhiễu Gaussian, ~15% NaN đồng bộ."
        ),
        "muc_phan_loai": {
            "home_ownership": HOME_OWNERSHIP_CATS,
            "purpose": PURPOSE_CATS,
            "verification_status": ["Verified", "Source Verified", "Not Verified"],
        },
        "nguong_bao_cao": NGUONG_BAO_CAO,
        "ghi_chu_nguong": (
            "Ngưỡng 0,5 CHỈ dùng để tính recall/precision/f1/accuracy khi báo cáo. "
            "Đường ra quyết định không cắt ngưỡng — tinh_diem_tong_hop() nhận PD liên tục."
        ),
        "chi_so": {
            "out_of_time": {
                "mo_ta": "Train các năm trước, validate năm kế tiếp — đo đúng tình huống triển khai thật",
                "folds": oot,
                "trung_binh": tb_oot,
            },
            "kfold_ngau_nhien": {
                "mo_ta": (
                    f"StratifiedKFold {SO_FOLD_NGAU_NHIEN} fold trộn đều mọi năm. Số CAO HƠN "
                    "out_of_time vì không kiểm tra khả năng tổng quát theo thời gian — "
                    "hai cách đo hai thứ khác nhau, không thay thế nhau."
                ),
                "n_folds": SO_FOLD_NGAU_NHIEN,
                "folds": kfold,
                "trung_binh": tb_kfold,
            },
            "tham_chieu_giai_doan_truoc": CHI_SO_THAM_CHIEU,
        },
    }

    print(f"\n[5/5] Lưu gói model")
    ket_qua = luu_mo_hinh(
        model,
        version=PHIEN_BAN,
        metrics=metrics_chinh,
        feature_names=FEATURE_NAMES,
        model_dir=THU_MUC_MO_HINH,
        thong_so_bo_sung=thong_so_bo_sung,
    )
    print(f"  {ket_qua['path']}")
    print(f"  SHA-256: {ket_qua['sha256']}")

    # ── Nghiệm thu ────────────────────────────────────────────────────────────
    print("\n" + "=" * 78)
    print("TỔNG KẾT")
    print("=" * 78)
    print(f"  Out-of-time  AUC = {tb_oot['auc_roc']['mean']:.4f} ± {tb_oot['auc_roc']['std']:.4f}")
    print(f"  K-fold       AUC = {tb_kfold['auc_roc']['mean']:.4f} ± {tb_kfold['auc_roc']['std']:.4f}")
    print(f"  Accuracy     = {tb_oot['accuracy']['mean']:.4f} "
          f"(baseline {tb_oot['accuracy_baseline']['mean']:.4f}, "
          f"chênh {tb_oot['chenh_so_voi_baseline']['mean']:+.4f})")

    fold_cuoi = oot[-1]
    auc_cu = CHI_SO_THAM_CHIEU["auc_roc"]
    chenh_lech = fold_cuoi["auc_roc"] - auc_cu
    gini_cu, gini_moi = 2 * auc_cu - 1, 2 * fold_cuoi["auc_roc"] - 1

    print(f"\n  SO SÁNH VỚI MÔ HÌNH THAM CHIẾU (fold '{fold_cuoi['ten']}'):")
    print(f"    v{PHIEN_BAN}: {len(FEATURE_NAMES)} đặc trưng (có CIC tổng hợp) : AUC {fold_cuoi['auc_roc']:.4f} · Gini {gini_moi:.4f}")
    print(f"    v7.0.0: {CHI_SO_THAM_CHIEU['so_dac_trung']} đặc trưng (có CIC/FICO gốc)  : AUC {auc_cu:.4f} · Gini {gini_cu:.4f}")
    print(f"    → Chênh lệch {chenh_lech:+.4f} AUC")
    print()
    print("    CIC tổng hợp từ FICO + nhiễu, không phải nguồn thật. Khi triển khai")
    print("    với điểm CIC thật từ cic-service, sức phân biệt có thể khác.")
    print("=" * 78)


if __name__ == "__main__":
    main()
