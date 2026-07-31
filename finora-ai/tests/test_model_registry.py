import json
import pytest
from pathlib import Path
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
import numpy as np

from app.ml.model_registry import (
    luu_mo_hinh,
    tai_mo_hinh,
    danh_sach_phien_ban,
    phien_ban_moi_nhat,
)


@pytest.fixture
def mo_hinh_lr():
    np.random.seed(42)
    X = np.random.randn(100, 5)
    y = (X[:, 0] > 0).astype(int)
    model = LogisticRegression(max_iter=200)
    model.fit(X, y)
    return model


@pytest.fixture
def metrics_mau():
    return {"auc_roc": 0.91, "gini": 0.82, "f1": 0.85, "recall": 0.78}


@pytest.fixture
def feature_names_mau():
    return ["f1", "f2", "f3", "f4", "f5"]


@pytest.fixture
def model_dir(tmp_path):
    return tmp_path / "models"


class TestLuuMoHinh:
    def test_luu_thanh_cong(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        ket_qua = luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        assert ket_qua["version"] == "1.0.0"
        assert "sha256" in ket_qua
        assert len(ket_qua["sha256"]) == 64
        assert Path(ket_qua["path"]).exists()

    def test_tao_file_pkl_va_json(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        assert (model_dir / "model_v1.0.0.pkl").exists()
        assert (model_dir / "model_v1.0.0.json").exists()

    def test_metadata_dung_noi_dung(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        meta = json.loads((model_dir / "model_v1.0.0.json").read_text())
        assert meta["version"] == "1.0.0"
        assert meta["model_class"] == "LogisticRegression"
        assert meta["metrics"]["auc_roc"] == 0.91
        assert meta["feature_names"] == feature_names_mau
        assert "saved_at" in meta

    def test_tao_thu_muc_neu_chua_co(self, mo_hinh_lr, metrics_mau, feature_names_mau, tmp_path):
        new_dir = tmp_path / "deep" / "nested" / "models"
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, new_dir)
        assert (new_dir / "model_v1.0.0.pkl").exists()


class TestThongSoBoSung:
    def test_ghi_them_thong_so_vao_metadata(
        self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir
    ):
        luu_mo_hinh(
            mo_hinh_lr, "7.0.0", metrics_mau, feature_names_mau, model_dir,
            thong_so_bo_sung={"median_dien_thieu": {"dti": 17.5}, "nguong_bao_cao": 0.5},
        )
        meta = json.loads((model_dir / "model_v7.0.0.json").read_text())
        assert meta["median_dien_thieu"] == {"dti": 17.5}
        assert meta["nguong_bao_cao"] == 0.5
        assert meta["version"] == "7.0.0"

    def test_khong_truyen_thi_metadata_giu_nguyen(
        self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir
    ):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        meta = json.loads((model_dir / "model_v1.0.0.json").read_text())
        assert set(meta) == {
            "version", "sha256", "metrics", "feature_names", "model_class", "saved_at",
        }

    @pytest.mark.parametrize("khoa", ["sha256", "version", "feature_names"])
    def test_chan_ghi_de_khoa_he_thong(
        self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir, khoa
    ):
        """Ghi đè âm thầm sha256/version sẽ làm metadata mô tả sai model thật."""
        with pytest.raises(ValueError, match="khóa hệ thống"):
            luu_mo_hinh(
                mo_hinh_lr, "7.0.0", metrics_mau, feature_names_mau, model_dir,
                thong_so_bo_sung={khoa: "gia_mao"},
            )


class TestTaiMoHinh:
    def test_tai_thanh_cong(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        model, metadata = tai_mo_hinh("1.0.0", model_dir)
        assert isinstance(model, LogisticRegression)
        assert metadata["version"] == "1.0.0"

    def test_mo_hinh_du_doan_duoc(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        model, _ = tai_mo_hinh("1.0.0", model_dir)
        X_test = np.random.RandomState(99).randn(5, 5)
        pred_goc = mo_hinh_lr.predict(X_test)
        pred_tai = model.predict(X_test)
        np.testing.assert_array_equal(pred_goc, pred_tai)

    def test_loi_khi_khong_tim_thay(self, model_dir):
        model_dir.mkdir(parents=True, exist_ok=True)
        with pytest.raises(FileNotFoundError):
            tai_mo_hinh("99.0.0", model_dir)


class TestDanhSachPhienBan:
    def test_danh_sach_rong(self, model_dir):
        model_dir.mkdir(parents=True, exist_ok=True)
        ds = danh_sach_phien_ban(model_dir)
        assert ds == []

    def test_nhieu_phien_ban(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        luu_mo_hinh(mo_hinh_lr, "1.1.0", metrics_mau, feature_names_mau, model_dir)
        luu_mo_hinh(mo_hinh_lr, "2.0.0", metrics_mau, feature_names_mau, model_dir)
        ds = danh_sach_phien_ban(model_dir)
        assert len(ds) == 3
        assert [m["version"] for m in ds] == ["1.0.0", "1.1.0", "2.0.0"]


class TestPhienBanMoiNhat:
    def test_khong_co_phien_ban(self, model_dir):
        model_dir.mkdir(parents=True, exist_ok=True)
        assert phien_ban_moi_nhat(model_dir) == "0.0.0"

    def test_co_phien_ban(self, mo_hinh_lr, metrics_mau, feature_names_mau, model_dir):
        luu_mo_hinh(mo_hinh_lr, "1.0.0", metrics_mau, feature_names_mau, model_dir)
        luu_mo_hinh(mo_hinh_lr, "2.0.0", metrics_mau, feature_names_mau, model_dir)
        assert phien_ban_moi_nhat(model_dir) == "2.0.0"
