"""Test hàm ánh xạ LendingClub → CIC fields."""
import numpy as np
import pandas as pd
import pytest

from app.ml.credit.preprocessing import map_nhom_no, tinh_so_thang_quan_he


class TestMapNhomNo:
    def test_binh_thuong(self):
        """pub_rec=0, acc_now_delinq=0 → nhóm 1."""
        result = map_nhom_no(pd.Series([0]), pd.Series([0]))
        assert result.iloc[0] == 1

    def test_co_tien_su(self):
        """pub_rec>0, acc_now_delinq=0 → nhóm 3."""
        result = map_nhom_no(pd.Series([2]), pd.Series([0]))
        assert result.iloc[0] == 3

    def test_dang_no_xau(self):
        """acc_now_delinq>0 → nhóm 4 (ưu tiên trên pub_rec)."""
        result = map_nhom_no(pd.Series([1]), pd.Series([2]))
        assert result.iloc[0] == 4

    def test_vectorized(self):
        pub = pd.Series([0, 1, 0, 3])
        delinq = pd.Series([0, 0, 1, 1])
        result = map_nhom_no(pub, delinq)
        assert list(result) == [1, 3, 4, 4]


class TestTinhSoThangQuanHe:
    def test_tinh_dung_so_thang(self):
        ecl = pd.Series(["Jan-10"])
        iss = pd.Series(["Jan-12"])
        result = tinh_so_thang_quan_he(ecl, iss)
        assert result.iloc[0] == 24

    def test_nan_khi_khong_co_ecl(self):
        ecl = pd.Series([np.nan])
        iss = pd.Series(["Jan-12"])
        result = tinh_so_thang_quan_he(ecl, iss)
        assert pd.isna(result.iloc[0])

    def test_khac_thang(self):
        ecl = pd.Series(["Mar-08"])
        iss = pd.Series(["Dec-12"])
        result = tinh_so_thang_quan_he(ecl, iss)
        assert result.iloc[0] == 57  # 4*12 + 9
