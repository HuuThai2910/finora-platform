"""Test service điều phối và endpoint POST /api/v1/ai/fraud/detect."""

import numpy as np
import pytest
from fastapi.testclient import TestClient
from xgboost import XGBClassifier

from app.api import fraud_router
from app.ml.fraud.features import FRAUD_FEATURE_NAMES, LOAI_GIAO_DICH_RUI_RO
from app.ml.fraud.predictor import BoPhatHienGianLan
from app.ml.shared.model_registry import luu_mo_hinh
from app.schemas.fraud import FraudDetectRequest
from app.services.fraud.service import cham_giao_dich
from main import app

PHIEN_BAN = "0.0.1-test"
DUONG_DAN = "/api/v1/ai/fraud/detect"


def _tao_bo_phat_hien(tmp_path) -> BoPhatHienGianLan:
    rng = np.random.default_rng(0)
    X = rng.random((400, len(FRAUD_FEATURE_NAMES))) * 1000
    y = (X[:, FRAUD_FEATURE_NAMES.index("so_tien")] > 500).astype(int)
    model = XGBClassifier(
        n_estimators=10, max_depth=3, random_state=0, eval_metric="aucpr", n_jobs=1
    )
    model.fit(X, y)
    luu_mo_hinh(
        model=model,
        version=PHIEN_BAN,
        metrics={"nguong_quyet_dinh": 0.5, "auc_pr": 0.9},
        feature_names=list(FRAUD_FEATURE_NAMES),
        model_dir=tmp_path,
        thong_so_bo_sung={
            "median_dien_thieu": {
                "dest_so_lan_nhan_truoc_do": 3.0,
                "dest_tong_tien_nhan_truoc_do": 1_500_000.0,
                "dest_so_nguoi_gui_khac_nhau_truoc_do": 2.0,
            },
            "he_so_quy_doi_tien": 1.0,
        },
    )
    return BoPhatHienGianLan.nap(PHIEN_BAN, tmp_path)


def _body(**ghi_de) -> dict:
    body = {
        "ma_giao_dich": "TXN-2026-0001",
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
    body.update(ghi_de)
    return body


@pytest.fixture
def bo_phat_hien(tmp_path):
    return _tao_bo_phat_hien(tmp_path)


@pytest.fixture
def client(monkeypatch, bo_phat_hien):
    """Thay bộ dự đoán thật bằng gói test, tránh phụ thuộc models/fraud/."""
    monkeypatch.setattr(fraud_router, "lay_bo_phat_hien_gian_lan", lambda: bo_phat_hien)
    return TestClient(app)


class TestPhamViMoHinh:
    """Mô hình chỉ học TRANSFER và CASH_OUT; loại khác phải bị chặn trước."""

    @pytest.mark.parametrize("loai", ["PAYMENT", "CASH_IN", "DEBIT"])
    def test_loai_ngoai_pham_vi_khong_cham_bang_mo_hinh(self, bo_phat_hien, loai):
        gd = FraudDetectRequest(**_body(loai_giao_dich=loai)).model_dump()
        kq = cham_giao_dich(bo_phat_hien, gd)
        assert kq["da_cham_bang_mo_hinh"] is False
        assert kq["fraud_probability"] == 0.0
        assert kq["bang_chung"] == []

    @pytest.mark.parametrize("loai", LOAI_GIAO_DICH_RUI_RO)
    def test_loai_trong_pham_vi_duoc_cham_bang_mo_hinh(self, bo_phat_hien, loai):
        gd = FraudDetectRequest(**_body(loai_giao_dich=loai)).model_dump()
        kq = cham_giao_dich(bo_phat_hien, gd)
        assert kq["da_cham_bang_mo_hinh"] is True

    def test_ngoai_pham_vi_van_kem_model_version_va_nguong(self, bo_phat_hien):
        gd = FraudDetectRequest(**_body(loai_giao_dich="PAYMENT")).model_dump()
        kq = cham_giao_dich(bo_phat_hien, gd)
        assert kq["model_version"] == PHIEN_BAN
        assert kq["nguong_quyet_dinh"] == bo_phat_hien.nguong


class TestEndpointDetect:
    def test_happy_path_tra_200(self, client):
        r = client.post(DUONG_DAN, json=_body())
        assert r.status_code == 200
        kq = r.json()
        assert kq["ma_giao_dich"] == "TXN-2026-0001"
        assert 0.0 <= kq["fraud_probability"] <= 1.0
        assert kq["model_version"] == PHIEN_BAN
        assert kq["da_cham_bang_mo_hinh"] is True

    def test_thieu_lich_su_vi_nhan_van_cham_duoc(self, client):
        """Payment chưa gửi behavior contract thì vẫn phải chấm được, dùng median."""
        body = _body()
        for cot in [
            "dest_so_lan_nhan_truoc_do",
            "dest_tong_tien_nhan_truoc_do",
            "dest_so_nguoi_gui_khac_nhau_truoc_do",
        ]:
            body.pop(cot)
        r = client.post(DUONG_DAN, json=body)
        assert r.status_code == 200
        assert r.json()["da_cham_bang_mo_hinh"] is True

    def test_response_khong_chua_truong_hanh_dong(self, client):
        """Ranh giới service: finora-ai không quyết định chặn/khóa, chỉ chấm điểm."""
        kq = client.post(DUONG_DAN, json=_body()).json()
        for truong in ["quyet_dinh", "decision", "hanh_dong", "action", "khoa_vi"]:
            assert truong not in kq

    @pytest.mark.parametrize(
        "ghi_de",
        [
            {"so_tien": 0},  # phải > 0
            {"so_tien": -5},
            {"so_du_truoc_gui": -1},  # phải >= 0
            {"gio_trong_ngay": 24},  # phải trong 0-23
            {"gio_trong_ngay": -1},
            {"loai_giao_dich": "KHONG_TON_TAI"},
            {"ma_giao_dich": ""},
            {"dest_so_lan_nhan_truoc_do": -1},
        ],
    )
    def test_du_lieu_sai_tra_422(self, client, ghi_de):
        assert client.post(DUONG_DAN, json=_body(**ghi_de)).status_code == 422

    def test_thieu_truong_bat_buoc_tra_422(self, client):
        body = _body()
        del body["so_du_truoc_gui"]
        assert client.post(DUONG_DAN, json=body).status_code == 422


class TestGoiModelHong:
    def test_khong_nap_duoc_goi_tra_503(self, monkeypatch):
        """Gói hỏng là lỗi cấu hình triển khai, không phải lỗi request → 503."""

        def _no_(*_args, **_kwargs):
            raise FileNotFoundError("models/fraud/model_v1.0.0.pkl không tồn tại")

        monkeypatch.setattr(fraud_router, "lay_bo_phat_hien_gian_lan", _no_)
        r = TestClient(app).post(DUONG_DAN, json=_body())
        assert r.status_code == 503
        assert r.json()["detail"]["code"] == "MODEL_NOT_AVAILABLE"

    def test_goi_lech_bo_dac_trung_tra_503(self, monkeypatch):
        def _no_(*_args, **_kwargs):
            raise ValueError(
                "Gói gian lận v1.0.0 có bộ đặc trưng khác fraud_features.py"
            )

        monkeypatch.setattr(fraud_router, "lay_bo_phat_hien_gian_lan", _no_)
        assert TestClient(app).post(DUONG_DAN, json=_body()).status_code == 503
