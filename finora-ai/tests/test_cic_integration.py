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
            "int_rate": 0.15,
            "term_months": 12,
            "dti": 15.5,
        }

    def test_cic_score_duoc_giu_khi_co(self, ho_so_co_ban):
        """Khi truyền cic_score, giá trị được giữ nguyên trong feature dict."""
        ho_so_co_ban["cic_score"] = 580
        # Chỉ test chuan_bi_dac_trung nếu có model. Nếu không có, skip.
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
        # cic_score phải được điền bằng median, không phải None
        assert row["cic_score"] is not None
        assert not (isinstance(row["cic_score"], float) and np.isnan(row["cic_score"]))


class TestDuDoanVoiCic:
    """du_doan() trả cic_score trong kết quả."""

    def test_ket_qua_co_cic_score(self):
        """Kết quả dict có key cic_score."""
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
        assert "cic_score" in ket_qua
        assert ket_qua["cic_score"] == 580

    def test_ket_qua_cic_score_none(self):
        """Khi không tra được CIC, cic_score trong kết quả = None."""
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
        ket_qua = bo.du_doan(ho_so, cic_score=None)
        assert ket_qua["cic_score"] is None


class TestDuDoanKhongTruyenCic:
    """du_doan() vẫn hoạt động bình thường khi không truyền cic_score (tương thích ngược)."""

    def test_khong_truyen_cic_score_khong_loi(self):
        """Gọi du_doan() không có cic_score (mặc định None) không raise lỗi TypeError."""
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
        assert ket_qua["cic_score"] is None


class TestRouterGoiCicClient:
    """score_credit() gọi CicClient khi có so_cccd, và bỏ qua khi không có."""

    @pytest.fixture
    def app_client(self, monkeypatch):
        """TestClient dùng app thật, nhưng có model giả để không phụ thuộc model v11."""
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
                    "cic_score": cic_score,
                }

        monkeypatch.setattr(credit_router, "lay_bo_du_doan", lambda: BoDuDoanGia())

        client = TestClient(main.app)
        yield client
        # monkeypatch tự phục hồi lay_bo_du_doan sau test; chỉ cần dọn cache của
        # lay_cic_client vì nó không bị monkeypatch thay thế.
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
        """Không có so_cccd → không gọi CicClient, cic_score trong response là None."""
        goi_duoc = {"count": 0}

        async def tra_diem_gia(self, so_cccd):
            goi_duoc["count"] += 1
            return 999

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = app_client.post("/api/v1/ai/credit/score", json=self._ho_so_co_ban())
        assert response.status_code == 200
        assert goi_duoc["count"] == 0
        assert response.json()["cic_score"] is None

    def test_co_so_cccd_goi_cic_va_tra_ve_diem(self, app_client, monkeypatch):
        """Có so_cccd → gọi CicClient.tra_diem_cic, kết quả đi vào response.cic_score."""

        async def tra_diem_gia(self, so_cccd):
            assert so_cccd == "012345678901"
            return 580

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = app_client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200
        assert response.json()["cic_score"] == 580

    def test_cic_service_tra_ve_none_khong_chan_luong(self, app_client, monkeypatch):
        """CicClient trả None (fail-open) → scoring vẫn chạy tiếp, cic_score=None."""

        async def tra_diem_gia(self, so_cccd):
            return None

        from app.services.cic_client import CicClient
        monkeypatch.setattr(CicClient, "tra_diem_cic", tra_diem_gia)

        response = app_client.post(
            "/api/v1/ai/credit/score",
            json=self._ho_so_co_ban(so_cccd="012345678901"),
        )
        assert response.status_code == 200
        assert response.json()["cic_score"] is None
