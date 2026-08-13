"""Test tích hợp: cic_score đi qua pipeline chuan_bi_dac_trung() → du_doan() → router."""
import numpy as np
import pytest
from fastapi.testclient import TestClient

from app.ml.predictor import BoDuDoan


class TestChuanBiDacTrungVoiCic:
    """chuan_bi_dac_trung() xử lý cic_score đúng."""

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
        }

    def test_cic_score_duoc_giu_khi_co(self, ho_so_co_ban):
        """Khi truyền cic_score, giá trị được giữ nguyên trong feature dict."""
        ho_so_co_ban["cic_score"] = 580
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score"] == 580
        assert row["cic_score_missing"] == 0.0

    def test_cic_score_missing_khi_none(self, ho_so_co_ban):
        """Khi không có cic_score, missing indicator = 1.0 và dùng median."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        row = bo.chuan_bi_dac_trung(ho_so_co_ban)
        assert row["cic_score_missing"] == 1.0
        assert row["cic_score"] is not None
        assert not (isinstance(row["cic_score"], float) and np.isnan(row["cic_score"]))


class TestDuDoanVoiCic:
    """du_doan() nhận cic_score nội bộ, không trả ra response."""

    def test_du_doan_voi_cic_khong_loi(self):
        """du_doan() với cic_score chạy bình thường."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ket_qua = bo.du_doan(ho_so, cic_score=580)
        assert "pd_probability" in ket_qua
        assert "cic_score" not in ket_qua

    def test_du_doan_khong_cic_khong_loi(self):
        """du_doan() không có cic_score vẫn chạy bình thường."""
        try:
            bo = BoDuDoan.nap()
        except (FileNotFoundError, ValueError):
            pytest.skip("Model chưa được train với cic_score")
        ho_so = {
            "annual_inc": 300_000_000,
            "loan_amnt": 50_000_000,
            "purpose": "debt_consolidation",
            "home_ownership": "MORTGAGE",
        }
        ket_qua = bo.du_doan(ho_so)
        assert "pd_probability" in ket_qua
        assert "cic_score" not in ket_qua


class TestRouterGoiCicClient:
    """score_credit() gọi CicClient khi có so_cccd, bỏ qua khi không có."""

    @pytest.fixture
    def app_client(self, monkeypatch):
        import main
        from app.api import credit_router

        credit_router.lay_bo_du_doan.cache_clear()
        credit_router.lay_cic_client.cache_clear()

        class BoDuDoanGia:
            metadata = {"version": "test"}

            def du_doan(self, ho_so, cic_score=None):
                return {
                    "pd_probability": 0.1,
                    "risk_score": 80,
                    "evaluation_score": 90.0,
                    "credit_grade": "A",
                    "suggested_limit": 50_000_000,
                    "decision": "APPROVED",
                    "rejection_reason": None,
                    "model_version": "test",
                }

        monkeypatch.setattr(credit_router, "lay_bo_du_doan", lambda: BoDuDoanGia())

        client = TestClient(main.app)
        yield client
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

    def test_khong_co_so_cccd_khong_goi_cic(self, app_client, monkeypatch):
        """Không có so_cccd → không gọi CicClient."""
        goi_duoc = {"count": 0}

        async def tra_diem_gia(self, so_cccd):
            goi_duoc["count"] += 1
            return 999

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = app_client.post("/api/v1/ai/credit/score", json=self._ho_so_co_ban())
        assert response.status_code == 200
        assert goi_duoc["count"] == 0
        assert "cic_score" not in response.json()

    def test_co_so_cccd_goi_cic(self, app_client, monkeypatch):
        """Có so_cccd → gọi CicClient.tra_diem_cic."""
        goi_duoc = {"count": 0}

        async def tra_diem_gia(self, so_cccd):
            goi_duoc["count"] += 1
            assert so_cccd == "012345678901"
            return 580

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = app_client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200
        assert goi_duoc["count"] == 1

    def test_cic_service_tra_ve_none_khong_chan_luong(self, app_client, monkeypatch):
        """CicClient trả None (fail-open) → scoring vẫn chạy tiếp."""

        async def tra_diem_gia(self, so_cccd):
            return None

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = app_client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200
