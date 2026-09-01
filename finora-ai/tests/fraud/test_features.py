"""Test bộ đặc trưng phát hiện gian lận giao dịch ví."""

from typing import ClassVar

import numpy as np
import pandas as pd
import pytest

from app.ml.fraud.features import (
    COT_LICH_SU_DICH_DEN,
    DAC_TRUNG_HANH_VI,
    DAC_TRUNG_RO_RI,
    FRAUD_FEATURE_NAMES,
    FRAUD_FEATURE_NAMES_DAY_DU,
    LOAI_GIAO_DICH_MAP,
    LOAI_GIAO_DICH_RUI_RO,
    tao_dac_trung,
    tao_dac_trung_mot_giao_dich,
    tinh_lich_su_dich_den,
)


@pytest.fixture
def nhat_ky() -> pd.DataFrame:
    """Năm giao dịch: bốn giao dịch tới ví D từ ba người gửi, một tới ví E."""
    return pd.DataFrame(
        {
            "step": [1, 2, 3, 4, 5],
            "type": ["TRANSFER", "TRANSFER", "CASH_OUT", "TRANSFER", "TRANSFER"],
            "amount": [100.0, 200.0, 300.0, 400.0, 500.0],
            "nameOrig": ["A", "B", "A", "C", "A"],
            "oldbalanceOrg": [100.0, 999.0, 300.0, 50.0, 500.0],
            "newbalanceOrig": [0.0, 799.0, 0.0, 0.0, 0.0],
            "nameDest": ["D", "D", "D", "E", "D"],
            "oldbalanceDest": [0.0, 100.0, 300.0, 0.0, 600.0],
            "newbalanceDest": [100.0, 300.0, 600.0, 0.0, 1100.0],
        }
    )


class TestDanhSachDacTrung:
    def test_bo_trien_khai_chi_gom_dac_trung_hanh_vi(self):
        assert FRAUD_FEATURE_NAMES == DAC_TRUNG_HANH_VI

    def test_bo_day_du_la_hanh_vi_cong_ro_ri(self):
        assert FRAUD_FEATURE_NAMES_DAY_DU == DAC_TRUNG_HANH_VI + DAC_TRUNG_RO_RI

    def test_khong_trung_lap(self):
        assert len(FRAUD_FEATURE_NAMES_DAY_DU) == len(set(FRAUD_FEATURE_NAMES_DAY_DU))

    def test_cot_ro_ri_khong_lot_vao_goi_trien_khai(self):
        """Bất biến quan trọng nhất của module: gói triển khai không được chứa
        đặc trưng phái sinh từ đẳng thức rò rỉ của trình mô phỏng."""
        for cot in DAC_TRUNG_RO_RI:
            assert cot not in FRAUD_FEATURE_NAMES

    def test_lich_su_dich_den_nam_trong_bo_trien_khai(self):
        for cot in COT_LICH_SU_DICH_DEN:
            assert cot in FRAUD_FEATURE_NAMES

    def test_loai_giao_dich_rui_ro_nam_trong_bang_ma_hoa(self):
        for loai in LOAI_GIAO_DICH_RUI_RO:
            assert loai in LOAI_GIAO_DICH_MAP


class TestLichSuDichDen:
    """Lịch sử phải tính tới NGAY TRƯỚC giao dịch, không gộp chính nó."""

    def test_giao_dich_dau_tien_toi_mot_vi_co_lich_su_bang_0(self, nhat_ky):
        h = tinh_lich_su_dich_den(nhat_ky)
        assert h.loc[0, "dest_so_lan_nhan_truoc_do"] == 0
        assert h.loc[0, "dest_tong_tien_nhan_truoc_do"] == 0
        assert h.loc[0, "dest_so_nguoi_gui_khac_nhau_truoc_do"] == 0

    def test_dem_so_lan_nhan_luy_ke(self, nhat_ky):
        h = tinh_lich_su_dich_den(nhat_ky)
        # Ví D nhận ở các dòng 0, 1, 2, 4
        assert list(h.loc[[0, 1, 2, 4], "dest_so_lan_nhan_truoc_do"]) == [0, 1, 2, 3]

    def test_tong_tien_khong_gom_giao_dich_hien_tai(self, nhat_ky):
        h = tinh_lich_su_dich_den(nhat_ky)
        assert list(h.loc[[0, 1, 2, 4], "dest_tong_tien_nhan_truoc_do"]) == [
            0.0,
            100.0,
            300.0,
            600.0,
        ]

    def test_dem_nguoi_gui_phan_biet(self, nhat_ky):
        """A gửi ở dòng 0 và 2 và 4 → tới dòng 4 chỉ có 2 người gửi khác nhau (A, B)
        đã từng gửi trước đó, không phải 3 lượt gửi."""
        h = tinh_lich_su_dich_den(nhat_ky)
        assert list(h.loc[[0, 1, 2, 4], "dest_so_nguoi_gui_khac_nhau_truoc_do"]) == [
            0,
            1,
            2,
            2,
        ]

    def test_moi_vi_nhan_doc_lap(self, nhat_ky):
        h = tinh_lich_su_dich_den(nhat_ky)
        assert h.loc[3, "dest_so_lan_nhan_truoc_do"] == 0

    def test_giu_nguyen_index_cua_dau_vao(self, nhat_ky):
        h = tinh_lich_su_dich_den(nhat_ky)
        assert list(h.index) == list(nhat_ky.index)


class TestTaoDacTrung:
    def test_sinh_du_moi_cot(self, nhat_ky):
        X = tao_dac_trung(nhat_ky)
        for cot in FRAUD_FEATURE_NAMES_DAY_DU:
            assert cot in X.columns

    def test_khong_con_nan(self, nhat_ky):
        X = tao_dac_trung(nhat_ky)
        assert not X[FRAUD_FEATURE_NAMES_DAY_DU].isna().any().any()

    def test_rut_can_tai_khoan_bat_dung_dang_thuc_ro_ri(self, nhat_ky):
        """amount == oldbalanceOrg ở các dòng 0, 2, 4."""
        X = tao_dac_trung(nhat_ky)
        assert list(X["rut_can_tai_khoan"]) == [1.0, 0.0, 1.0, 0.0, 1.0]

    def test_gio_va_ngay_suy_tu_step(self, nhat_ky):
        d = nhat_ky.copy()
        d["step"] = [1, 25, 26, 48, 49]
        X = tao_dac_trung(d)
        assert list(X["gio_trong_ngay"]) == [1.0, 1.0, 2.0, 0.0, 1.0]
        assert list(X["ngay_trong_thang"]) == [0.0, 1.0, 1.0, 2.0, 2.0]

    def test_la_gio_dem_dung_khung_0h_den_6h(self, nhat_ky):
        d = nhat_ky.copy()
        d["step"] = [0, 5, 6, 12, 23]
        X = tao_dac_trung(d)
        assert list(X["la_gio_dem"]) == [1.0, 1.0, 0.0, 0.0, 0.0]

    def test_log_khong_am_khi_so_du_am(self, nhat_ky):
        """PaySim có số dư âm ở vài dòng; log1p của số âm sẽ ra NaN nếu không clip."""
        d = nhat_ky.copy()
        d["oldbalanceOrg"] = [-50.0, 999.0, 300.0, 50.0, 500.0]
        X = tao_dac_trung(d)
        assert np.isfinite(X["log_so_du_truoc_gui"]).all()


class TestTaoDacTrungMotGiaoDich:
    """Đường chấm điểm trực tuyến phải khớp đúng đường huấn luyện."""

    MEDIAN: ClassVar[dict[str, float]] = {
        "dest_so_lan_nhan_truoc_do": 3.0,
        "dest_tong_tien_nhan_truoc_do": 1_500_000.0,
        "dest_so_nguoi_gui_khac_nhau_truoc_do": 2.0,
    }

    def _giao_dich(self, **ghi_de) -> dict:
        gd = {
            "loai_giao_dich": "TRANSFER",
            "so_tien": 181_000.0,
            "so_du_truoc_gui": 181_000.0,
            "so_du_truoc_nhan": 0.0,
            "gio_trong_ngay": 2,
            "ngay_trong_thang": 15,
            "dest_so_lan_nhan_truoc_do": 0,
            "dest_tong_tien_nhan_truoc_do": 0.0,
            "dest_so_nguoi_gui_khac_nhau_truoc_do": 0,
        }
        gd.update(ghi_de)
        return gd

    def test_sinh_dung_bo_dac_trung_trien_khai(self):
        dt = tao_dac_trung_mot_giao_dich(self._giao_dich(), self.MEDIAN)
        assert set(dt) == set(FRAUD_FEATURE_NAMES)

    def test_khong_sinh_cot_ro_ri(self):
        dt = tao_dac_trung_mot_giao_dich(self._giao_dich(), self.MEDIAN)
        for cot in DAC_TRUNG_RO_RI:
            assert cot not in dt

    def test_thieu_lich_su_thi_dien_median_cua_goi(self):
        """Bất biến chống train/serve skew: giá trị điền phải đến từ gói model,
        không phải hằng số viết cứng trong code."""
        gd = self._giao_dich(
            dest_so_lan_nhan_truoc_do=None,
            dest_tong_tien_nhan_truoc_do=None,
            dest_so_nguoi_gui_khac_nhau_truoc_do=None,
        )
        dt = tao_dac_trung_mot_giao_dich(gd, self.MEDIAN)
        for cot in COT_LICH_SU_DICH_DEN:
            assert dt[cot] == self.MEDIAN[cot]

    def test_co_lich_su_thi_khong_dien_median(self):
        dt = tao_dac_trung_mot_giao_dich(
            self._giao_dich(dest_so_lan_nhan_truoc_do=7), self.MEDIAN
        )
        assert dt["dest_so_lan_nhan_truoc_do"] == 7.0

    def test_gia_tri_0_khong_bi_coi_la_thieu(self):
        """0 lần nhận là thông tin thật (ví hoàn toàn mới) — không được nhầm
        thành thiếu rồi thay bằng median 3.0."""
        dt = tao_dac_trung_mot_giao_dich(
            self._giao_dich(dest_so_lan_nhan_truoc_do=0), self.MEDIAN
        )
        assert dt["dest_so_lan_nhan_truoc_do"] == 0.0

    def test_so_du_nhan_bo_trong_thi_coi_la_0(self):
        dt = tao_dac_trung_mot_giao_dich(
            self._giao_dich(so_du_truoc_nhan=None), self.MEDIAN
        )
        assert dt["so_du_truoc_nhan"] == 0.0
        assert dt["dest_so_du_bang_0"] == 1.0

    def test_khop_voi_duong_huan_luyen(self):
        """Cùng một giao dịch đi qua `tao_dac_trung` (huấn luyện) và
        `tao_dac_trung_mot_giao_dich` (chấm điểm) phải ra cùng giá trị."""
        df = pd.DataFrame(
            {
                "step": [26],  # 26 % 24 = 2 giờ, 26 // 24 = 1 ngày
                "type": ["TRANSFER"],
                "amount": [181_000.0],
                "nameOrig": ["A"],
                "oldbalanceOrg": [181_000.0],
                "newbalanceOrig": [0.0],
                "nameDest": ["B"],
                "oldbalanceDest": [0.0],
                "newbalanceDest": [0.0],
            }
        )
        X = tao_dac_trung(df)
        dt = tao_dac_trung_mot_giao_dich(
            self._giao_dich(ngay_trong_thang=1), self.MEDIAN
        )
        for cot in FRAUD_FEATURE_NAMES:
            assert dt[cot] == pytest.approx(X.loc[0, cot]), f"lệch ở cột {cot}"
