"""Test danh mục trường dữ liệu mà luật chấm điểm được phép đọc.

Danh mục là "nửa code" duy nhất còn lại của rule engine sau khi luật trở thành
dữ liệu: admin chọn trường từ đây, không tự gõ tên trường. Nên test tập trung vào
hai việc: danh mục có đúng những trường hệ thống thực sự thu thập được, và hàm đọc
trả None (không phải 0, không phải NaN) khi hồ sơ thiếu dữ liệu.
"""

import math

import pytest

from app.services.credit.truong_du_lieu import (
    DANH_MUC_TRUONG,
    TruongDuLieu,
    _lay_ty_le_tra_no_thang,
    _so_hoac_none,
    _tinh_tien_tra_thang,
    mo_ta_danh_muc,
)

# Tên các trường bắt buộc phải có, gom theo nguồn để đối chiếu với schema
# CreditScoreRequest, cic_client và các cột dẫn xuất trong features.py.
TRUONG_HO_SO = {
    "annual_inc",
    "loan_amnt",
    "person_age",
    "emp_length_years",
    "dti",
    "installment",
    "home_ownership",
    "purpose",
    "verification_status",
}
TRUONG_CIC = {
    "cic_score",
    "so_lan_tre_han",
    "thang_tu_tre_gan_nhat",
    "tong_du_no",
    "du_no_the_tin_dung",
    "ty_le_su_dung_the",
    "so_lan_tra_cuu",
    "so_hop_dong_dang_co",
    "so_thang_quan_he",
    "nhom_no_cao_nhat",
}
TRUONG_DAN_XUAT = {"ty_le_tra_no_thang", "loan_to_income", "ty_le_du_no_thu_nhap"}


class TestDanhMuc:
    def test_co_du_cac_truong_he_thong_thu_thap_duoc(self):
        assert TRUONG_HO_SO | TRUONG_CIC | TRUONG_DAN_XUAT | {"term_months"} <= set(
            DANH_MUC_TRUONG
        )

    def test_int_rate_khong_duoc_dua_vao_danh_muc(self):
        """Lãi suất là target leakage — admin không được tạo luật chấm theo nó."""
        assert "int_rate" not in DANH_MUC_TRUONG

    def test_khoa_trung_voi_ma_truong(self):
        for ma, truong in DANH_MUC_TRUONG.items():
            assert isinstance(truong, TruongDuLieu)
            assert truong.ma == ma

    def test_truong_phan_loai_phai_co_gia_tri_hop_le(self):
        for truong in DANH_MUC_TRUONG.values():
            if truong.kieu == "phan_loai":
                assert len(truong.gia_tri_hop_le) >= 2, truong.ma
            else:
                assert truong.gia_tri_hop_le == (), truong.ma

    def test_nha_o_dung_mien_gia_tri_cua_schema(self):
        assert set(DANH_MUC_TRUONG["home_ownership"].gia_tri_hop_le) == {
            "OWN",
            "MORTGAGE",
            "RENT",
            "OTHER",
        }

    def test_moi_truong_deu_co_mo_ta_va_nguon(self):
        for truong in DANH_MUC_TRUONG.values():
            assert truong.mo_ta, truong.ma
            assert truong.nguon in {"ho_so", "cic", "fineract", "dan_xuat"}, truong.ma

    def test_mo_ta_danh_muc_khong_chua_ham(self):
        """Bản công bố qua API phải JSON hoá được — không mang theo callable."""
        ds = mo_ta_danh_muc()
        assert [d["ma"] for d in ds] == list(DANH_MUC_TRUONG)
        for d in ds:
            assert set(d) == {
                "ma",
                "mo_ta",
                "kieu",
                "nguon",
                "don_vi",
                "la_ty_le",
                "gia_tri_hop_le",
                "nhom_shap",
            }
            assert not callable(d.get("trich_xuat"))


class TestDocGiaTri:
    def test_truong_so_doc_dung_gia_tri(self):
        assert DANH_MUC_TRUONG["cic_score"].doc({"cic_score": 720}) == 720.0

    def test_truong_so_thieu_tra_none(self):
        assert DANH_MUC_TRUONG["cic_score"].doc({}) is None

    def test_truong_so_nan_coi_la_thieu(self):
        assert DANH_MUC_TRUONG["dti"].doc({"dti": math.nan}) is None

    @pytest.mark.parametrize("gia_tri", ["OWN", "RENT"])
    def test_truong_phan_loai_doc_gia_tri_hop_le(self, gia_tri):
        assert (
            DANH_MUC_TRUONG["home_ownership"].doc({"home_ownership": gia_tri})
            == gia_tri
        )

    def test_truong_phan_loai_gia_tri_la_coi_la_thieu(self):
        assert (
            DANH_MUC_TRUONG["home_ownership"].doc({"home_ownership": "CASTLE"}) is None
        )

    @pytest.mark.parametrize(
        "chuoi,mong_doi", [("10+ years", 10.0), ("< 1 year", 0.5), ("5 years", 5.0)]
    )
    def test_tham_nien_doc_tu_chuoi(self, chuoi, mong_doi):
        assert (
            DANH_MUC_TRUONG["emp_length_years"].doc({"emp_length": chuoi}) == mong_doi
        )

    def test_tham_nien_thieu_tra_none(self):
        assert DANH_MUC_TRUONG["emp_length_years"].doc({}) is None

    def test_loan_to_income(self):
        ho_so = {"loan_amnt": 50_000_000, "annual_inc": 200_000_000}
        assert DANH_MUC_TRUONG["loan_to_income"].doc(ho_so) == pytest.approx(0.25)

    def test_loan_to_income_thu_nhap_khong_tra_none(self):
        assert (
            DANH_MUC_TRUONG["loan_to_income"].doc({"loan_amnt": 1, "annual_inc": 0})
            is None
        )

    def test_ty_le_du_no_thu_nhap(self):
        ho_so = {"tong_du_no": 100_000_000, "annual_inc": 200_000_000}
        assert DANH_MUC_TRUONG["ty_le_du_no_thu_nhap"].doc(ho_so) == pytest.approx(0.5)

    def test_ty_le_tra_no_thang_uu_tien_installment(self):
        ho_so = {"annual_inc": 120_000_000, "installment": 2_000_000}
        assert DANH_MUC_TRUONG["ty_le_tra_no_thang"].doc(ho_so) == pytest.approx(0.2)

    def test_truong_ty_le_danh_dau_la_ty_le(self):
        """Gợi ý cho người vay nhân 100 với các trường 0–1 — phải đánh dấu đúng."""
        assert DANH_MUC_TRUONG["ty_le_tra_no_thang"].la_ty_le is True
        assert DANH_MUC_TRUONG["loan_to_income"].la_ty_le is True
        # DTI và tỷ lệ dùng thẻ đã ở thang phần trăm.
        assert DANH_MUC_TRUONG["dti"].la_ty_le is False
        assert DANH_MUC_TRUONG["ty_le_su_dung_the"].la_ty_le is False


class TestHamTienIch:
    @pytest.mark.parametrize("dau_vao", [None, "abc", float("nan")])
    def test_so_hoac_none_loai_gia_tri_khong_dung(self, dau_vao):
        assert _so_hoac_none(dau_vao) is None

    @pytest.mark.parametrize("dau_vao,mong_doi", [(5, 5.0), ("12.5", 12.5), (0, 0.0)])
    def test_so_hoac_none_giu_gia_tri_hop_le(self, dau_vao, mong_doi):
        assert _so_hoac_none(dau_vao) == mong_doi

    def test_tinh_tien_tra_thang_lai_suat_khong(self):
        """Lãi 0% thì chia đều gốc, không được chia cho 0."""
        tien = _tinh_tien_tra_thang(
            {"loan_amnt": 12_000_000, "term_months": 12, "int_rate": 0}
        )
        assert tien == pytest.approx(1_000_000)

    def test_tien_tra_thang_tang_dan_theo_lai_suat(self):
        """Lãi suất cao hơn thì trả nhiều hơn — ở MỌI khoảng, kể cả quanh mốc 1,0."""
        chung = {"loan_amnt": 12_000_000, "term_months": 12}
        tien = [
            _tinh_tien_tra_thang({**chung, "int_rate": ls})
            for ls in (0.5, 1.0, 2.0, 5.0, 12.0, 18.0)
        ]
        assert tien == sorted(tien), f"tiền trả không tăng đơn điệu: {tien}"

    def test_int_rate_luon_doc_la_phan_tram_nam(self):
        """`int_rate` là %/năm đúng như schema khai, không đoán theo độ lớn."""
        chung = {"loan_amnt": 12_000_000, "term_months": 12}
        assert _tinh_tien_tra_thang({**chung, "int_rate": 12.0}) == pytest.approx(
            1_066_185, rel=1e-4
        )
        assert _tinh_tien_tra_thang({**chung, "int_rate": 0.12}) == pytest.approx(
            1_000_650, rel=1e-4
        )

    def test_thieu_du_lieu_thi_khong_tinh_duoc(self):
        assert _tinh_tien_tra_thang({"loan_amnt": 12_000_000}) is None
        assert _lay_ty_le_tra_no_thang({"installment": 1_000_000}) is None

    def test_tu_tinh_khi_thieu_installment(self):
        """Không có installment thì tính từ lãi suất và kỳ hạn."""
        ho_so = {
            "annual_inc": 120_000_000,
            "loan_amnt": 12_000_000,
            "term_months": 12,
            "int_rate": 12.0,
        }
        assert _lay_ty_le_tra_no_thang(ho_so) > 0

    def test_thu_nhap_bang_khong_coi_la_thieu(self):
        """Chia cho 0 phải trả None, không được crash hay ra vô cực."""
        assert (
            _lay_ty_le_tra_no_thang({"annual_inc": 0, "installment": 5_000_000}) is None
        )
