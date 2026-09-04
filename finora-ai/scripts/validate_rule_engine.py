"""Kiểm chứng rule engine trên dữ liệu vay thật có nhãn vỡ nợ.

Chạy:  python scripts/validate_rule_engine.py [--n 150000]

Trả lời hai câu hỏi mà chỉ đọc code không trả lời được:

  1. Điểm luật có tương quan với vỡ nợ thật không, hay chỉ là con số cho có?
     Đo bằng AUC và bằng bảng tỷ lệ vỡ nợ theo từng khoảng điểm.
  2. Khi cic-service chết, luật còn phân biệt được không?

Dữ liệu: data/lc_clean.csv (LendingClub, cột loan_status là nhãn vỡ nợ thật).
Các cột LendingClub được ánh xạ sang tên trường CIC mà FINORA dùng thật:

    fico_score      → cic_score          (điểm tín dụng quốc gia)
    inq_last_6mths  → so_lan_tra_cuu     (số lần tra cứu 6 tháng)

Ánh xạ này hợp lệ vì hai bên đo cùng một khái niệm nghiệp vụ; thang điểm khác
nhau nhưng ngưỡng trong BO_LUAT được đặt theo thang FICO/CIC vốn tương đương
(cùng khoảng 300-850).

Script thoát mã 1 nếu không đạt ngưỡng chấp nhận, để cắm được vào CI.
"""
import argparse
import itertools
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.services.credit.rule_engine import cham_diem_chi_tiet

DUONG_DAN_DATA = Path(__file__).resolve().parent.parent / "data" / "lc_clean.csv"

# Ngưỡng chấp nhận. AUC 0,5 là đoán bừa; rule engine không cần bằng ML (0,70)
# nhưng phải rõ ràng hơn ngẫu nhiên thì mới đáng đưa vào điểm tổng hợp.
AUC_TOI_THIEU = 0.62
AUC_TOI_THIEU_KHI_MAT_CIC = 0.56


def _nap_du_lieu(n: int) -> pd.DataFrame:
    if not DUONG_DAN_DATA.exists():
        print(f"Không tìm thấy {DUONG_DAN_DATA}", file=sys.stderr)
        sys.exit(1)
    d = pd.read_csv(DUONG_DAN_DATA, nrows=n)
    # Ánh xạ tên cột LendingClub sang tên trường CIC mà rule engine đọc.
    d["cic_score"] = d["fico_score"]
    d["so_lan_tra_cuu"] = d["inq_last_6mths"]
    return d


def _cham(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    """Trả về (điểm rủi ro, số luật có dữ liệu) cho từng hồ sơ."""
    diem, du_lieu = [], []
    for ho_so in df.to_dict("records"):
        d, vet = cham_diem_chi_tiet(ho_so)
        diem.append(d)
        du_lieu.append(sum(1 for m in vet if not m["thieu_du_lieu"]))
    return np.array(diem), np.array(du_lieu)


def _in_bang_bad_rate(diem: np.ndarray, y: np.ndarray) -> bool:
    """In tỷ lệ vỡ nợ theo khoảng điểm. Trả True nếu đơn điệu giảm."""
    print(f"  {'Khoảng điểm':<14}{'Số hồ sơ':>10}{'Tỷ lệ vỡ nợ':>14}")
    print("  " + "─" * 38)
    ty_le = []
    for lo in range(0, 101, 10):
        mask = (diem >= lo) & (diem < lo + 10)
        if mask.sum() < 100:
            continue
        bad = 100 * y[mask].mean()
        ty_le.append(bad)
        print(f"  {lo:3d} – {lo + 9:<7}{mask.sum():>10,}{bad:>13.2f}%")
    don_dieu = all(a >= b for a, b in itertools.pairwise(ty_le))
    print(f"\n  Đơn điệu giảm: {'ĐẠT' if don_dieu else 'KHÔNG ĐẠT'}")
    return don_dieu


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--n", type=int, default=150_000, help="Số hồ sơ đọc từ dataset")
    args = parser.parse_args()

    d = _nap_du_lieu(args.n)
    y = d["loan_status"].values
    print(f"Dữ liệu: {len(d):,} hồ sơ · tỷ lệ vỡ nợ thực tế {100 * y.mean():.2f}%\n")

    diem, so_luat = _cham(d)
    auc = roc_auc_score(y, -diem)

    print("── Sức phân biệt ──")
    print(f"  AUC                : {auc:.4f}  (ngưỡng {AUC_TOI_THIEU})")
    print(f"  Thang điểm sử dụng : {diem.min()} – {diem.max()} / 100")
    print(f"  Trung bình số luật có dữ liệu: {so_luat.mean():.2f} / 5\n")

    print("── Tỷ lệ vỡ nợ theo khoảng điểm ──")
    don_dieu = _in_bang_bad_rate(diem, y)

    print("\n── Độ bền khi cic-service chết ──")
    d_mat_cic = d.assign(cic_score=np.nan, so_lan_tra_cuu=np.nan)
    diem_mat_cic, _ = _cham(d_mat_cic)
    auc_mat_cic = roc_auc_score(y, -diem_mat_cic)
    print(f"  AUC khi mất CIC    : {auc_mat_cic:.4f}  (ngưỡng {AUC_TOI_THIEU_KHI_MAT_CIC})")
    print(f"  Thang điểm còn lại : {diem_mat_cic.min()} – {diem_mat_cic.max()} / 100")

    dat = [
        (auc >= AUC_TOI_THIEU, f"AUC {auc:.4f} ≥ {AUC_TOI_THIEU}"),
        (don_dieu, "tỷ lệ vỡ nợ đơn điệu giảm theo điểm"),
        (
            auc_mat_cic >= AUC_TOI_THIEU_KHI_MAT_CIC,
            f"AUC khi mất CIC {auc_mat_cic:.4f} ≥ {AUC_TOI_THIEU_KHI_MAT_CIC}",
        ),
    ]

    print("\n── Kết luận ──")
    for ok, mo_ta in dat:
        print(f"  [{'✓' if ok else '✗'}] {mo_ta}")

    if all(ok for ok, _ in dat):
        print("\nĐẠT toàn bộ ngưỡng chấp nhận.")
        return 0
    print("\nKHÔNG ĐẠT — rule engine cần xem lại trước khi triển khai.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
