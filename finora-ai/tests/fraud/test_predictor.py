"""Test bộ dự đoán gian lận và các bất biến của gói model.

Các test ở đây tự dựng một gói model nhỏ trong thư mục tạm thay vì dùng gói thật
trong `models/fraud/`. Lý do: test phải chạy được trên máy vừa clone repo về, nơi
`data/paysim.csv` không tồn tại (thư mục `data/` bị gitignore) nên không ai huấn
luyện lại được. Gói nhỏ vẫn kiểm chứng đúng các bất biến cần kiểm.
"""

import json

import numpy as np
import pytest
from xgboost import XGBClassifier

from app.ml.fraud.features import COT_LICH_SU_DICH_DEN, FRAUD_FEATURE_NAMES
from app.ml.fraud.predictor import SO_BANG_CHUNG_TOI_DA, BoPhatHienGianLan
from app.ml.shared.model_registry import (
    _duong_dan_metadata,
    _duong_dan_mo_hinh,
    luu_mo_hinh,
)

PHIEN_BAN = "0.0.1-test"

MEDIAN_TEST = {
    "dest_so_lan_nhan_truoc_do": 3.0,
    "dest_tong_tien_nhan_truoc_do": 1_500_000.0,
    "dest_so_nguoi_gui_khac_nhau_truoc_do": 2.0,
}


def _tao_goi(tmp_path, he_so_quy_doi: float = 1.0, **ghi_de_metadata):
    """Dựng một gói model gian lận tối giản nhưng hợp lệ."""
    rng = np.random.default_rng(0)
    n = 400
    X = rng.random((n, len(FRAUD_FEATURE_NAMES))) * 1000

    # Nhãn phụ thuộc cột `so_tien` để mô hình có tín hiệu thật để học, nhờ đó
    # đóng góp TreeSHAP khác 0 và test bằng chứng mới có ý nghĩa.
    i_so_tien = FRAUD_FEATURE_NAMES.index("so_tien")
    y = (X[:, i_so_tien] > 500).astype(int)

    model = XGBClassifier(
        n_estimators=10, max_depth=3, random_state=0, eval_metric="aucpr", n_jobs=1
    )
    model.fit(X, y)

    thong_so = {
        "median_dien_thieu": dict(MEDIAN_TEST),
        "he_so_quy_doi_tien": he_so_quy_doi,
    }
    thong_so.update(ghi_de_metadata)

    luu_mo_hinh(
        model=model,
        version=PHIEN_BAN,
        metrics={"nguong_quyet_dinh": 0.5, "auc_pr": 0.9},
        feature_names=list(FRAUD_FEATURE_NAMES),
        model_dir=tmp_path,
        thong_so_bo_sung=thong_so,
    )
    return tmp_path


def _giao_dich(**ghi_de) -> dict:
    gd = {
        "loai_giao_dich": "TRANSFER",
        "so_tien": 900.0,
        "so_du_truoc_gui": 900.0,
        "so_du_truoc_nhan": 0.0,
        "gio_trong_ngay": 2,
        "ngay_trong_thang": 15,
        "dest_so_lan_nhan_truoc_do": 0,
        "dest_tong_tien_nhan_truoc_do": 0.0,
        "dest_so_nguoi_gui_khac_nhau_truoc_do": 0,
    }
    gd.update(ghi_de)
    return gd


@pytest.fixture
def bo_phat_hien(tmp_path):
    _tao_goi(tmp_path)
    return BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)


class TestNapGoi:
    """Gói lệch code phải raise ngay lúc nạp, không được âm thầm chấm sai."""

    def test_nap_goi_hop_le(self, tmp_path):
        _tao_goi(tmp_path)
        bo = BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)
        assert bo.feature_names == FRAUD_FEATURE_NAMES
        assert bo.nguong == 0.5

    def test_thieu_median_thi_raise(self, tmp_path):
        _tao_goi(tmp_path)
        meta_path = _duong_dan_metadata(PHIEN_BAN, tmp_path)
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        del meta["median_dien_thieu"]
        meta_path.write_text(json.dumps(meta), encoding="utf-8")

        with pytest.raises(ValueError, match="median_dien_thieu"):
            BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)

    def test_thieu_nguong_thi_raise(self, tmp_path):
        _tao_goi(tmp_path)
        meta_path = _duong_dan_metadata(PHIEN_BAN, tmp_path)
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        del meta["metrics"]["nguong_quyet_dinh"]
        meta_path.write_text(json.dumps(meta), encoding="utf-8")

        with pytest.raises(ValueError, match="nguong_quyet_dinh"):
            BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)

    def test_lech_bo_dac_trung_thi_raise(self, tmp_path):
        _tao_goi(tmp_path)
        meta_path = _duong_dan_metadata(PHIEN_BAN, tmp_path)
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta["feature_names"] = meta["feature_names"][:-1]
        meta_path.write_text(json.dumps(meta), encoding="utf-8")

        with pytest.raises(ValueError, match="bộ đặc trưng"):
            BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)

    def test_file_pkl_bi_thay_thi_raise(self, tmp_path):
        """SHA-256 không khớp nghĩa là file model đã bị thay hoặc hỏng."""
        _tao_goi(tmp_path)
        pkl = _duong_dan_mo_hinh(PHIEN_BAN, tmp_path)
        pkl.write_bytes(pkl.read_bytes() + b"\x00")

        with pytest.raises(ValueError, match="SHA-256"):
            BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)


class TestChuanBiDacTrung:
    def test_thieu_lich_su_dien_median_cua_goi(self, bo_phat_hien):
        gd = _giao_dich(
            dest_so_lan_nhan_truoc_do=None,
            dest_tong_tien_nhan_truoc_do=None,
            dest_so_nguoi_gui_khac_nhau_truoc_do=None,
        )
        dt = bo_phat_hien.chuan_bi_dac_trung(gd)
        for cot in COT_LICH_SU_DICH_DEN:
            assert dt[cot] == MEDIAN_TEST[cot]

    def test_he_so_quy_doi_duoc_ap_dung_cho_cot_tien(self, tmp_path):
        _tao_goi(tmp_path, he_so_quy_doi=1000.0)
        bo = BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)
        dt = bo.chuan_bi_dac_trung(
            _giao_dich(so_tien=900_000.0, so_du_truoc_gui=450_000.0)
        )
        assert dt["so_tien"] == 900.0
        assert dt["so_du_truoc_gui"] == 450.0

    def test_he_so_bang_1_thi_giu_nguyen(self, bo_phat_hien):
        dt = bo_phat_hien.chuan_bi_dac_trung(_giao_dich(so_tien=900.0))
        assert dt["so_tien"] == 900.0

    def test_khong_sua_dict_dau_vao(self, tmp_path):
        """Quy đổi tiền không được ghi đè lên dict của người gọi."""
        _tao_goi(tmp_path, he_so_quy_doi=1000.0)
        bo = BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)
        gd = _giao_dich(so_tien=900_000.0)
        bo.chuan_bi_dac_trung(gd)
        assert gd["so_tien"] == 900_000.0


class TestDuDoan:
    def test_tra_du_truong_bat_buoc(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich())
        for truong in [
            "fraud_probability",
            "muc_rui_ro",
            "nguong_quyet_dinh",
            "vuot_nguong",
            "bang_chung",
            "model_version",
        ]:
            assert truong in kq

    def test_xac_suat_nam_trong_khoang_0_1(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich())
        assert 0.0 <= kq["fraud_probability"] <= 1.0

    def test_luon_kem_model_version(self, bo_phat_hien):
        """Bất biến của `07-service-boundaries.md`: response phải có model version."""
        assert bo_phat_hien.du_doan(_giao_dich())["model_version"] == PHIEN_BAN

    def test_vuot_nguong_nhat_quan_voi_xac_suat(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich())
        assert kq["vuot_nguong"] == (kq["fraud_probability"] >= kq["nguong_quyet_dinh"])

    def test_muc_rui_ro_nam_trong_ba_gia_tri(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich())
        assert kq["muc_rui_ro"] in {"THAP", "TRUNG_BINH", "CAO"}

    def test_vuot_nguong_thi_muc_rui_ro_la_cao(self, bo_phat_hien):
        """Gói test học quy luật so_tien > 500 → số tiền lớn phải ra rủi ro CAO."""
        kq = bo_phat_hien.du_doan(_giao_dich(so_tien=990.0))
        assert kq["vuot_nguong"] is True
        assert kq["muc_rui_ro"] == "CAO"


class TestBangChung:
    def test_khong_vuot_qua_so_dong_toi_da(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich())
        assert len(kq["bang_chung"]) <= SO_BANG_CHUNG_TOI_DA

    def test_chi_giu_dong_gop_duong(self, bo_phat_hien):
        """Người rà soát cần lý do nghi ngờ, không cần lý do khiến nó có vẻ an toàn."""
        kq = bo_phat_hien.du_doan(_giao_dich(so_tien=990.0))
        for bc in kq["bang_chung"]:
            assert bc["muc_dong_gop"] > 0

    def test_sap_xep_giam_dan_theo_dong_gop(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich(so_tien=990.0))
        muc = [bc["muc_dong_gop"] for bc in kq["bang_chung"]]
        assert muc == sorted(muc, reverse=True)

    def test_dac_trung_trong_bang_chung_thuoc_goi(self, bo_phat_hien):
        kq = bo_phat_hien.du_doan(_giao_dich(so_tien=990.0))
        for bc in kq["bang_chung"]:
            assert bc["dac_trung"] in FRAUD_FEATURE_NAMES
            assert bc["mo_ta"]

    def test_gia_tri_bang_chung_khop_dac_trung_thuc_te(self, bo_phat_hien):
        gd = _giao_dich(so_tien=990.0)
        dt = bo_phat_hien.chuan_bi_dac_trung(gd)
        kq = bo_phat_hien.du_doan(gd)
        for bc in kq["bang_chung"]:
            assert bc["gia_tri"] == pytest.approx(dt[bc["dac_trung"]])
