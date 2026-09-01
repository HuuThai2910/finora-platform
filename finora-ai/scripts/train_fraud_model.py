"""
Huấn luyện mô hình phát hiện gian lận giao dịch ví cho FINORA AI Service.

Dữ liệu: PaySim (`data/paysim.csv`, 6.362.620 giao dịch, 30 ngày), nhật ký mô
phỏng của một dịch vụ mobile money. Schema trùng nghiệp vụ ví `finora-payment`.

Ba quyết định thiết kế, mỗi quyết định đều dựa trên số đo chứ không phỏng đoán:

1. **Chỉ giữ TRANSFER và CASH_OUT.** Đo trên toàn bộ dữ liệu: TRANSFER 0,769%
   gian lận, CASH_OUT 0,184%, còn PAYMENT/CASH_IN/DEBIT đúng 0 ca. Giữ ba loại
   không-bao-giờ-gian-lận chỉ làm loãng tập và thổi phồng accuracy.

2. **Chia theo thời gian, không chia ngẫu nhiên.** Huấn luyện trên các bước sớm,
   validate trên các bước muộn — đúng tình huống triển khai thật. Cùng triết lý
   out-of-time với `scripts/train_credit_model.py`.

3. **Huấn luyện HAI mô hình.** PaySim chứa một đẳng thức rò rỉ:
   `amount == oldbalanceOrg` đúng với 97,82% giao dịch gian lận và 0,00% giao
   dịch bình thường. Một câu lệnh `if` đã gần như giải xong bài toán. Mô hình học
   nhóm cột này đạt chỉ số gần tuyệt đối trên PaySim nhưng không học được gì về
   gian lận thật.

   - `hanh_vi` — 14 đặc trưng hành vi. **Đây là gói đem triển khai.**
   - `day_du` — 18 đặc trưng, thêm nhóm rò rỉ. Chỉ dùng làm mốc đối chứng để
     lượng hóa "phần chỉ số đến từ artifact của trình mô phỏng".

   Cả hai bộ chỉ số đều được ghi vào gói model, để người đọc báo cáo thấy được
   chênh lệch thay vì chỉ thấy con số đẹp.

Ngưỡng cắt được chọn trên tập **hiệu chỉnh** tách riêng, không phải trên tập
validate — chọn ngưỡng rồi báo cáo trên chính tập đó là tự chấm bài của mình.
Từ v1.1.0, ngưỡng tối ưu theo **F2** thay vì F1: xem `BETA_NGUONG`. Gói còn kèm
`bang_danh_doi_nguong` để `finora-payment` tự chọn điểm vận hành khác nếu muốn.

Cách dùng:
    cd finora-ai
    python scripts/train_fraud_model.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import pandas as pd

from app.ml.fraud.features import (
    COT_LICH_SU_DICH_DEN,
    FRAUD_FEATURE_NAMES,
    FRAUD_FEATURE_NAMES_DAY_DU,
    LOAI_GIAO_DICH_RUI_RO,
    tao_dac_trung,
    tinh_lich_su_dich_den,
)
from app.ml.fraud.training import XGB_FRAUD_PARAMS, fit_xgboost_gian_lan
from app.ml.shared.evaluation import (
    chon_nguong_toi_uu_fbeta,
    danh_gia_theo_nguong,
    nguong_dat_recall,
)
from app.ml.shared.model_registry import luu_mo_hinh

PHIEN_BAN = "1.1.0"
GOC = Path(__file__).resolve().parent.parent
DATA_FILE = GOC / "data" / "paysim.csv"
THU_MUC_MO_HINH = GOC / "models" / "fraud"

# Tỷ lệ chia theo thời gian. Ranh giới được nắn về biên `step` để không có bước
# thời gian nào bị cắt đôi giữa hai tập.
TY_LE_TRAIN = 0.70
TY_LE_HIEU_CHINH = 0.10

# Hệ số quy đổi tiền tệ. Xem `ghi_chu_don_vi_tien` trong gói model: PaySim không
# công bố đơn vị tiền, nên giữ nguyên thang gốc thay vì bịa ra một tỷ giá.
HE_SO_QUY_DOI_TIEN = 1.0

# Beta của F-beta dùng để chọn ngưỡng mặc định. v1.0.0 dùng beta = 1 (tối ưu F1),
# tức coi bỏ lọt gian lận và báo động nhầm tốn kém ngang nhau — điều không đúng với
# nghiệp vụ: bỏ lọt là mất tiền thật và phải bồi thường, còn báo nhầm chỉ khiến
# khách nhập thêm một bước xác thực. v1.1.0 chuyển sang beta = 2.
#
# Đo được trên tập validate khi đổi beta 1 → 2: recall 0,8006 → 0,8255 (+2,5 điểm)
# trong khi precision chỉ giảm 0,9417 → 0,9106 (−3,1 điểm), và F1 vẫn nhỉnh hơn.
BETA_NGUONG = 2.0

# Các mức recall dựng bảng đánh đổi ghi vào gói model, để `finora-payment` tự chọn
# điểm vận hành. Ngưỡng mặc định của gói chỉ là điểm khởi đầu hợp lý, không phải
# quyết định chính sách — theo `07-service-boundaries.md`, policy thuộc Payment.
MUC_RECALL_BANG_DANH_DOI = (0.85, 0.90, 0.95, 0.99)


def nap_va_chuan_hoa() -> pd.DataFrame:
    """Nạp PaySim, dựng đặc trưng, rồi lọc còn hai loại giao dịch rủi ro."""
    print(f"  Nạp {DATA_FILE.name}...")
    d = pd.read_csv(
        DATA_FILE,
        dtype={
            "step": "int32",
            "type": "category",
            "amount": "float64",
            "nameOrig": "object",
            "oldbalanceOrg": "float64",
            "newbalanceOrig": "float64",
            "nameDest": "object",
            "oldbalanceDest": "float64",
            "newbalanceDest": "float64",
            "isFraud": "int8",
        },
        usecols=[
            "step",
            "type",
            "amount",
            "nameOrig",
            "oldbalanceOrg",
            "newbalanceOrig",
            "nameDest",
            "oldbalanceDest",
            "newbalanceDest",
            "isFraud",
        ],
    )
    print(
        f"  {len(d):,} giao dịch × {len(d.columns)} cột, gian lận {d.isFraud.mean():.4%}"
    )

    # Lịch sử tài khoản nhận phải tính TRƯỚC khi lọc loại giao dịch: một tài khoản
    # mule nhận tiền từ nhiều loại khác nhau, lọc trước sẽ xóa mất phần lịch sử đó.
    print("  Tính lịch sử tài khoản nhận trên toàn bộ nhật ký...")
    lich_su = tinh_lich_su_dich_den(d)

    print("  Dựng đặc trưng...")
    X = tao_dac_trung(d, lich_su)
    X["isFraud"] = d["isFraud"].astype("int8")
    X["step"] = d["step"]

    truoc = len(X)
    mask = d["type"].isin(LOAI_GIAO_DICH_RUI_RO).to_numpy()
    X = X.loc[mask].reset_index(drop=True)
    print(
        f"  Lọc còn {', '.join(LOAI_GIAO_DICH_RUI_RO)}: {truoc:,} → {len(X):,} dòng, "
        f"gian lận {X.isFraud.mean():.4%}"
    )
    return X


def chia_theo_thoi_gian(
    d: pd.DataFrame,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Chia train/hiệu chỉnh/validate theo trục thời gian `step`."""
    dem = d.groupby("step").size().sort_index()
    luy_ke = dem.cumsum() / len(d)
    step_train = int(luy_ke[luy_ke >= TY_LE_TRAIN].index[0])
    step_hieu_chinh = int(luy_ke[luy_ke >= TY_LE_TRAIN + TY_LE_HIEU_CHINH].index[0])

    train = d[d.step <= step_train]
    hieu_chinh = d[(d.step > step_train) & (d.step <= step_hieu_chinh)]
    val = d[d.step > step_hieu_chinh]

    print(
        f"  Ranh giới step: train ≤ {step_train} | hiệu chỉnh ≤ {step_hieu_chinh} | validate > {step_hieu_chinh}"
    )
    for ten, tap in [("train", train), ("hiệu chỉnh", hieu_chinh), ("validate", val)]:
        print(
            f"    {ten:11s} n={len(tap):>9,}  gian lận={int(tap.isFraud.sum()):>5,} "
            f"({tap.isFraud.mean():.4%})"
        )
    return train, hieu_chinh, val


def dung_bang_danh_doi(y_hieu_chinh, p_hieu_chinh, y_val, p_val) -> list[dict]:
    """Bảng đánh đổi recall–precision để `finora-payment` chọn điểm vận hành.

    Ngưỡng của mỗi hàng được chọn trên tập **hiệu chỉnh**, rồi đo lại trên tập
    **validate**. Đọc thẳng ngưỡng từ đường cong của validate sẽ cho bảng đẹp hơn
    nhưng vô dụng: đó là hiệu năng của một ngưỡng đã biết trước đáp án.

    Vì vậy cột `recall` hiếm khi bằng đúng `muc_recall_nham` — chênh lệch giữa hai
    cột chính là mức mà ngưỡng không chuyển được từ tập hiệu chỉnh sang dữ liệu mới,
    và đó là thông tin cần thấy chứ không phải khuyết điểm cần che.
    """
    bang = []
    for muc in MUC_RECALL_BANG_DANH_DOI:
        nguong = nguong_dat_recall(y_hieu_chinh, p_hieu_chinh, muc)
        chi_so = danh_gia_theo_nguong(y_val, p_val, nguong)
        bang.append(
            {
                "muc_recall_nham": muc,
                "nguong": nguong,
                "recall": chi_so["recall"],
                "precision": chi_so["precision"],
                "f1": chi_so["f1"],
                "accuracy": chi_so["accuracy"],
                "chenh_so_voi_baseline": chi_so["chenh_so_voi_baseline"],
            }
        )
    return bang


def huan_luyen_mot_bo(
    ten: str,
    cot: list[str],
    train: pd.DataFrame,
    hieu_chinh: pd.DataFrame,
    val: pd.DataFrame,
) -> tuple[object, dict, float]:
    """Huấn luyện một bộ đặc trưng, chọn ngưỡng trên tập hiệu chỉnh, đo trên validate."""
    print(f"\n  ── Bộ '{ten}' ({len(cot)} đặc trưng) " + "─" * 30)
    model, spw = fit_xgboost_gian_lan(
        train[cot].to_numpy(), train["isFraud"].to_numpy()
    )

    p_hieu_chinh = model.predict_proba(hieu_chinh[cot].to_numpy())[:, 1]
    y_hieu_chinh = hieu_chinh["isFraud"].to_numpy()
    nguong = chon_nguong_toi_uu_fbeta(y_hieu_chinh, p_hieu_chinh, beta=BETA_NGUONG)

    p_val = model.predict_proba(val[cot].to_numpy())[:, 1]
    y_val = val["isFraud"].to_numpy()
    chi_so = danh_gia_theo_nguong(y_val, p_val, nguong)
    chi_so["beta_chon_nguong"] = BETA_NGUONG

    print(f"     scale_pos_weight = {spw:.1f}   ngưỡng = {nguong:.6f}")
    print(
        f"     AUC-PR={chi_so['auc_pr']:.4f} (baseline {chi_so['auc_pr_baseline']:.4f})  "
        f"AUC-ROC={chi_so['auc_roc']:.4f}  KS={chi_so['ks_statistic']:.4f}"
    )
    print(
        f"     Precision={chi_so['precision']:.4f}  Recall={chi_so['recall']:.4f}  "
        f"F1={chi_so['f1']:.4f}"
    )
    print(
        f"     Accuracy={chi_so['accuracy']:.4f}  (baseline {chi_so['accuracy_baseline']:.4f}, "
        f"chênh {chi_so['chenh_so_voi_baseline']:+.4f})"
    )

    bang = dung_bang_danh_doi(y_hieu_chinh, p_hieu_chinh, y_val, p_val)
    print(
        f"     {'Nhắm recall':>12} {'Ngưỡng':>10} {'Recall':>9} {'Precision':>10} {'Acc vs BL':>10}"
    )
    for hang in bang:
        print(
            f"     {hang['muc_recall_nham']:>11.0%} {hang['nguong']:>10.6f} "
            f"{hang['recall']:>9.4f} {hang['precision']:>10.4f} "
            f"{hang['chenh_so_voi_baseline']:>+10.4f}"
        )
    return model, chi_so, spw, bang


def main() -> None:
    print("\n[1/4] Nạp và chuẩn hóa dữ liệu")
    d = nap_va_chuan_hoa()

    print("\n[2/4] Chia tập theo thời gian")
    train, hieu_chinh, val = chia_theo_thoi_gian(d)

    print("\n[3/4] Huấn luyện và đánh giá")
    model_hv, chi_so_hv, spw_hv, bang_hv = huan_luyen_mot_bo(
        "hanh_vi", FRAUD_FEATURE_NAMES, train, hieu_chinh, val
    )
    _, chi_so_dd, _, _ = huan_luyen_mot_bo(
        "day_du", FRAUD_FEATURE_NAMES_DAY_DU, train, hieu_chinh, val
    )

    print("\n[4/4] Lưu gói model 'hanh_vi'")
    # Median của lịch sử tài khoản nhận, tính CHỈ trên tập train. Predictor dùng
    # đúng ba giá trị này khi Payment không gửi kèm lịch sử — nếu lấy median của
    # toàn bộ dữ liệu thì tập validate rò sang tập train.
    median = {cot: float(train[cot].median()) for cot in COT_LICH_SU_DICH_DEN}

    ket_qua = luu_mo_hinh(
        model=model_hv,
        version=PHIEN_BAN,
        metrics=chi_so_hv,
        feature_names=FRAUD_FEATURE_NAMES,
        model_dir=THU_MUC_MO_HINH,
        thong_so_bo_sung={
            "bai_toan": "phat_hien_gian_lan_giao_dich_vi",
            "median_dien_thieu": median,
            "he_so_quy_doi_tien": HE_SO_QUY_DOI_TIEN,
            "ghi_chu_don_vi_tien": (
                "PaySim không công bố đơn vị tiền tệ của nhật ký gốc, nên các cột "
                "tiền được giữ NGUYÊN THANG của PaySim và hệ số quy đổi đặt bằng "
                "1,0. Khác với mô hình tín dụng — nơi LendingClub là USD nên quy "
                "đổi sang VND bằng hệ số thu nhập trung bình có căn cứ — ở đây "
                "không có mốc nào để neo, và bịa ra một hệ số sẽ tệ hơn là để ngỏ. "
                "HỆ QUẢ: predictor nhận số tiền trên thang PaySim, không phải VND. "
                "Trước khi chạy thật với dữ liệu ví FINORA, MUST hiệu chỉnh lại hệ "
                "số này (hoặc huấn luyện lại trên dữ liệu thật); bỏ qua bước đó sẽ "
                "gây train/serve skew mà service không hề báo lỗi."
            ),
            "sieu_tham_so": {**XGB_FRAUD_PARAMS, "scale_pos_weight": spw_hv},
            "du_lieu_huan_luyen": {
                "nguon": "data/paysim.csv",
                "loc": f"type ∈ {list(LOAI_GIAO_DICH_RUI_RO)}",
                "n_dong": len(d),
                "n_train": len(train),
                "n_hieu_chinh": len(hieu_chinh),
                "n_validate": len(val),
                "ty_le_gian_lan": float(d.isFraud.mean()),
                "chia_tap": "theo thời gian (step), không xáo trộn",
            },
            "doi_chung_ro_ri": {
                "mo_ta": (
                    "PaySim chứa đẳng thức rò rỉ amount == oldbalanceOrg, đúng với "
                    "97,82% giao dịch gian lận và 0,00% giao dịch bình thường. Bộ "
                    "'day_du' thêm 4 cột phái sinh từ đẳng thức này. Chênh lệch giữa "
                    "hai bộ chính là phần hiệu năng đến từ artifact của trình mô "
                    "phỏng chứ không từ hành vi gian lận."
                ),
                "dac_trung_bi_loai": [
                    c
                    for c in FRAUD_FEATURE_NAMES_DAY_DU
                    if c not in FRAUD_FEATURE_NAMES
                ],
                "chi_so_day_du": chi_so_dd,
            },
            "bang_danh_doi_nguong": {
                "mo_ta": (
                    "Điểm vận hành thay thế, ngưỡng chọn trên tập hiệu chỉnh rồi đo "
                    "trên tập validate. finora-payment dùng bảng này để chọn ngưỡng "
                    "theo khẩu vị rủi ro của mình thay vì bị buộc theo ngưỡng mặc "
                    "định của gói. Lưu ý cột chenh_so_voi_baseline: từ mức recall "
                    "95% trở lên nó chuyển sang ÂM, nghĩa là mô hình bắt đầu kém "
                    "hơn cả việc đoán 'không giao dịch nào gian lận'."
                ),
                "cac_diem_van_hanh": bang_hv,
            },
            "ghi_chu_nguong": (
                f"Ngưỡng chọn bằng tối đa hóa F-beta với beta = {BETA_NGUONG:g} trên "
                "tập hiệu chỉnh (tách khỏi validate). Beta = 2 coi recall quan trọng "
                "gấp đôi precision, phản ánh bất đối xứng của nghiệp vụ: bỏ lọt gian "
                "lận là mất tiền thật, còn báo động nhầm chỉ tốn thêm một bước xác "
                "thực. Payment áp policy trên score; finora-ai không tự khóa tài "
                "khoản hay chặn giao dịch."
            ),
        },
    )
    print(f"  Đã lưu: {ket_qua['path']}")
    print(f"  SHA-256: {ket_qua['sha256'][:16]}...")

    print("\n── Đối chứng rò rỉ " + "─" * 52)
    print(f"  {'Chỉ số':<12} {'hanh_vi (triển khai)':>22} {'day_du (có rò rỉ)':>20}")
    for k in ["auc_pr", "precision", "recall", "f1", "accuracy"]:
        print(f"  {k:<12} {chi_so_hv[k]:>22.4f} {chi_so_dd[k]:>20.4f}")


if __name__ == "__main__":
    main()
