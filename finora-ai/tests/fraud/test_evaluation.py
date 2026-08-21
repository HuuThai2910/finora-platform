"""Test các hàm chọn ngưỡng cắt cho bài toán mất cân bằng."""

import numpy as np
import pytest

from app.ml.shared.evaluation import (
    chon_nguong_toi_uu_fbeta,
    danh_gia_theo_nguong,
    nguong_dat_recall,
)


@pytest.fixture(scope="module")
def du_lieu_lech():
    """4.000 giao dịch, ~2% dương, điểm số có tín hiệu thật nhưng chồng lấn.

    Cỡ mẫu giữ nhỏ có chủ đích: `chon_nguong_toi_uu_fbeta` quét 400 ngưỡng ứng
    viên nên chi phí tỉ lệ thuận với số dòng, mà các bất biến được kiểm ở đây
    (thứ tự ngưỡng theo beta, ngưỡng theo mức recall) không cần dữ liệu lớn mới
    bộc lộ. `scope="module"` để không phải dựng lại cho từng test.
    """
    rng = np.random.default_rng(0)
    y = (rng.random(4_000) < 0.02).astype(int)
    p = np.clip(rng.random(4_000) * 0.3 + y * 0.6, 0, 1)
    return y, p


class TestChonNguongToiUuFbeta:
    def test_beta_lon_hon_cho_nguong_thap_hon(self, du_lieu_lech):
        """Bất biến định nghĩa nên F-beta: coi trọng recall hơn thì phải hạ ngưỡng."""
        y, p = du_lieu_lech
        assert chon_nguong_toi_uu_fbeta(y, p, beta=2.0) <= chon_nguong_toi_uu_fbeta(
            y, p, beta=1.0
        )

    def test_beta_lon_hon_cho_recall_khong_thap_hon(self, du_lieu_lech):
        y, p = du_lieu_lech
        r1 = danh_gia_theo_nguong(y, p, chon_nguong_toi_uu_fbeta(y, p, beta=1.0))[
            "recall"
        ]
        r2 = danh_gia_theo_nguong(y, p, chon_nguong_toi_uu_fbeta(y, p, beta=2.0))[
            "recall"
        ]
        assert r2 >= r1

    def test_beta_mac_dinh_la_1(self, du_lieu_lech):
        y, p = du_lieu_lech
        assert chon_nguong_toi_uu_fbeta(y, p) == chon_nguong_toi_uu_fbeta(
            y, p, beta=1.0
        )

    def test_nguong_nam_trong_khoang_hop_le(self, du_lieu_lech):
        y, p = du_lieu_lech
        assert 0.0 <= chon_nguong_toi_uu_fbeta(y, p, beta=2.0) <= 1.0


class TestNguongDatRecall:
    @pytest.mark.parametrize("muc", [0.50, 0.90, 0.99])
    def test_dat_duoc_muc_recall_yeu_cau(self, du_lieu_lech, muc):
        y, p = du_lieu_lech
        nguong = nguong_dat_recall(y, p, muc)
        assert danh_gia_theo_nguong(y, p, nguong)["recall"] >= muc - 0.01

    def test_muc_recall_cao_hon_cho_nguong_thap_hon(self, du_lieu_lech):
        y, p = du_lieu_lech
        assert nguong_dat_recall(y, p, 0.99) <= nguong_dat_recall(y, p, 0.50)

    def test_khong_dat_duoc_thi_tra_0(self, du_lieu_lech):
        """Recall > 1 là bất khả thi; trả 0,0 ('gắn cờ tất cả') để người gọi thấy rõ
        thay vì nhận một ngưỡng trông hợp lệ."""
        y, p = du_lieu_lech
        assert nguong_dat_recall(y, p, 1.5) == 0.0


class TestDanhGiaTheoNguong:
    def test_co_du_chi_so_cho_du_lieu_lech(self, du_lieu_lech):
        y, p = du_lieu_lech
        chi_so = danh_gia_theo_nguong(y, p, 0.5)
        for khoa in [
            "auc_pr",
            "auc_pr_baseline",
            "precision",
            "recall",
            "accuracy",
            "accuracy_baseline",
            "chenh_so_voi_baseline",
            "nguong_quyet_dinh",
        ]:
            assert khoa in chi_so

    def test_auc_pr_baseline_bang_ty_le_lop_duong(self, du_lieu_lech):
        """Mô hình ngây thơ có AUC-PR đúng bằng tỷ lệ lớp dương — đây là mốc phải
        vượt qua, khác hẳn mốc 0,5 của ROC-AUC."""
        y, p = du_lieu_lech
        assert danh_gia_theo_nguong(y, p, 0.5)["auc_pr_baseline"] == pytest.approx(
            y.mean()
        )

    def test_chenh_so_voi_baseline_dung_cong_thuc(self, du_lieu_lech):
        y, p = du_lieu_lech
        chi_so = danh_gia_theo_nguong(y, p, 0.5)
        assert chi_so["chenh_so_voi_baseline"] == pytest.approx(
            chi_so["accuracy"] - chi_so["accuracy_baseline"]
        )

    def test_nguong_thap_hon_khong_lam_giam_recall(self, du_lieu_lech):
        y, p = du_lieu_lech
        cao = danh_gia_theo_nguong(y, p, 0.8)["recall"]
        thap = danh_gia_theo_nguong(y, p, 0.4)["recall"]
        assert thap >= cao
