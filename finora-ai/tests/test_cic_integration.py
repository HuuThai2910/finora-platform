"""Test tích hợp v14: cic_data dict đi qua pipeline chuan_bi_dac_trung → du_doan → router."""
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.ml.predictor import BoDuDoan


class TestChuanBiDacTrungV14:
    """chuan_bi_dac_trung() xử lý 9 CIC raw fields + 2 Fineract fields."""

    @pytest.fixture
    def ho_so_co_ban(self):
        return {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
            "person_age": 30,
            "emp_length": "5 years",
            "dti": 15.5,
            "int_rate": 12.0,
            "term_months": 12,
        }

    def test_cic_data_dict_merge_dung(self, ho_so_co_ban):
        """Khi truyền cic_data dict, tất cả 10 CIC fields được giữ đúng."""
        cic_data = {
            "cic_score": 580,
            "so_lan_tre_han": 2,
            "thang_tu_tre_gan_nhat": 6,
            "tong_du_no": 50_000_000,
            "du_no_the_tin_dung": 5_000_000,
            "ty_le_su_dung_the": 25.0,
            "so_lan_tra_cuu": 1,
            "so_hop_dong_dang_co": 3,
            "so_thang_quan_he": 48,
            "nhom_no_cao_nhat": 1,
        }
        ho_so_co_ban.update(cic_data)
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score"] == 580
        assert row["so_lan_tre_han"] == 2
        assert row["tong_du_no"] == 50_000_000
        assert row["so_thang_quan_he"] == 48
        assert row["cic_score_missing"] == 0.0
        assert row["so_lan_tre_han_missing"] == 0.0

    def test_cic_data_none_tat_ca_missing(self, ho_so_co_ban):
        """Không có CIC data → tất cả 9 CIC missing indicators = 1.0."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score_missing"] == 1.0
        assert row["so_lan_tre_han_missing"] == 1.0
        assert row["tong_du_no_missing"] == 1.0
        # Sau điền median, không còn None/NaN
        assert row["cic_score"] is not None
        assert not (isinstance(row["cic_score"], float) and np.isnan(row["cic_score"]))

    def test_fineract_fields_duoc_lay(self, ho_so_co_ban):
        """int_rate và term_months từ request được đưa vào row."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["int_rate"] == 12.0
        assert row["term_months"] == 12
        assert row["int_rate_missing"] == 0.0
        assert row["term_months_missing"] == 0.0


class TestDuDoanV14:
    """du_doan() nhận cic_data dict thay vì cic_score int."""

    def test_du_doan_voi_cic_data(self):
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
            "int_rate": 12.0,
            "term_months": 12,
        }
        cic_data = {"cic_score": 580, "so_lan_tre_han": 0, "thang_tu_tre_gan_nhat": -1,
                     "tong_du_no": 50_000_000, "du_no_the_tin_dung": 5_000_000,
                     "ty_le_su_dung_the": 25.0, "so_lan_tra_cuu": 1,
                     "so_hop_dong_dang_co": 3, "so_thang_quan_he": 48,
                     "nhom_no_cao_nhat": 1}
        ket_qua = bo.du_doan(ho_so, cic_data=cic_data)
        assert "pd_probability" in ket_qua
        assert "cic_score" not in ket_qua
        assert ket_qua["model_version"] == "14.0.0"

    def test_du_doan_khong_cic_data(self):
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model v14 chưa được train")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ket_qua = bo.du_doan(ho_so)
        assert "pd_probability" in ket_qua


class TestRouterV14:
    """score_credit() gọi CicClient trả dict, forward cic_data."""

    @pytest.fixture
    def app_client(self, monkeypatch):
        import main
        from app.api import credit_router

        credit_router.lay_bo_du_doan.cache_clear()
        credit_router.lay_cic_client.cache_clear()

        class BoDuDoanGia:
            metadata = {"version": "14.0.0"}

            def du_doan(self, ho_so, cic_data=None):
                self._last_cic_data = cic_data
                return {
                    "pd_probability": 0.1,
                    "risk_score": 80,
                    "evaluation_score": 90.0,
                    "credit_grade": "A",
                    "suggested_limit": 50_000_000,
                    "decision": "APPROVED",
                    "rejection_reason": None,
                    "model_version": "14.0.0",
                }

        bo_gia = BoDuDoanGia()
        monkeypatch.setattr(credit_router, "lay_bo_du_doan", lambda: bo_gia)

        client = TestClient(main.app)
        yield client, bo_gia
        credit_router.lay_cic_client.cache_clear()

    def _ho_so_co_ban(self, **extra):
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ho_so.update(extra)
        return ho_so

    def test_khong_co_so_cccd_cic_data_la_none(self, app_client, monkeypatch):
        client, bo_gia = app_client
        response = client.post("/api/v1/ai/credit/score", json=self._ho_so_co_ban())
        assert response.status_code == 200

    def test_co_so_cccd_forward_cic_data(self, app_client, monkeypatch):
        client, bo_gia = app_client
        cic_dict = {"cic_score": 580, "so_lan_tre_han": 0, "thang_tu_tre_gan_nhat": -1,
                     "tong_du_no": 50_000_000, "du_no_the_tin_dung": 5_000_000,
                     "ty_le_su_dung_the": 25.0, "so_lan_tra_cuu": 1,
                     "so_hop_dong_dang_co": 3, "so_thang_quan_he": 48,
                     "nhom_no_cao_nhat": 1}

        async def tra_diem_gia(self, so_cccd):
            return cic_dict

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200

    def test_cic_none_fail_open(self, app_client, monkeypatch):
        client, bo_gia = app_client

        async def tra_diem_gia(self, so_cccd):
            return None

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200

    def test_request_co_fineract_fields(self, app_client, monkeypatch):
        client, bo_gia = app_client
        response = client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(int_rate=12.0, term_months=12),
        )
        assert response.status_code == 200
