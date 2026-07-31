"""
Test API chấm điểm — chạy trên GÓI MODEL THẬT trong `models/`.

Khác `test_predictor.py` (dùng gói giả, không đụng file thật): các test ở đây xác
nhận gói đã huấn luyện thực sự nạp và chấm điểm được qua HTTP. Nếu chưa chạy
`scripts/train_final_model.py` thì toàn bộ được skip thay vì báo lỗi đỏ.
"""
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from main import app

GOI_MODEL = Path(__file__).resolve().parent.parent / "models" / "model_v8.0.0.pkl"

pytestmark = pytest.mark.skipif(
    not GOI_MODEL.exists(),
    reason="Chưa có models/model_v8.0.0.pkl — chạy: python scripts/train_final_model.py",
)

DUONG_DAN = "/api/v1/ai/credit/score"


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def ho_so_tot():
    return {
        "person_age": 30,
        "emp_length": "10+ years",
        "annual_inc": 500_000_000,
        "loan_amnt": 50_000_000,
        "home_ownership": "OWN",
        "purpose": "debt_consolidation",
    }


class TestChamDiem:
    def test_ho_so_hop_le_tra_200(self, client, ho_so_tot):
        r = client.post(DUONG_DAN, json=ho_so_tot)
        assert r.status_code == 200, r.text
        kq = r.json()
        assert 0.0 < kq["pd_probability"] < 1.0
        assert kq["credit_grade"] in ("A", "B", "C", "D")
        assert kq["decision"] in ("APPROVED", "PENDING_REVIEW", "REJECTED")
        assert kq["model_version"] == "8.0.0"

    def test_lai_suat_khong_vuot_tran_phap_ly(self, client, ho_so_tot):
        """Điều 468 Bộ luật Dân sự 2015 — trần lãi suất thỏa thuận 20%/năm."""
        assert client.post(DUONG_DAN, json=ho_so_tot).json()["suggested_rate"] <= 0.20

    def test_han_muc_khong_vuot_tran_nen_tang(self, client, ho_so_tot):
        """Nghị định 94/2025 — tối đa 100 triệu đồng trên một nền tảng."""
        assert client.post(DUONG_DAN, json=ho_so_tot).json()["suggested_limit"] <= 100_000_000

    def test_chi_khai_4_truong_bat_buoc_van_cham_duoc(self, client):
        """`person_age` và `emp_length` bỏ trống → lấy từ median trong gói."""
        r = client.post(DUONG_DAN, json={
            "annual_inc": 200_000_000, "loan_amnt": 30_000_000,
            "purpose": "education", "home_ownership": "RENT",
        })
        assert r.status_code == 200, r.text
        assert 0.0 < r.json()["pd_probability"] < 1.0

    def test_ho_so_xau_cham_diem_thap_hon_ho_so_tot(self, client, ho_so_tot):
        """Kiểm tra hệ thống xếp hạng đúng hướng, không trả số ngẫu nhiên."""
        ho_so_xau = {
            "person_age": 22, "emp_length": "< 1 year",
            "annual_inc": 60_000_000, "loan_amnt": 55_000_000,
            "home_ownership": "OTHER", "purpose": "vacation",
        }
        tot = client.post(DUONG_DAN, json=ho_so_tot).json()
        xau = client.post(DUONG_DAN, json=ho_so_xau).json()
        assert xau["evaluation_score"] < tot["evaluation_score"], (
            f"Hồ sơ xấu ({xau['evaluation_score']}) phải thấp điểm hơn "
            f"hồ sơ tốt ({tot['evaluation_score']})"
        )


class TestKiemTraDauVao:
    def test_thu_nhap_bang_0_tra_422(self, client, ho_so_tot):
        assert client.post(DUONG_DAN, json={**ho_so_tot, "annual_inc": 0}).status_code == 422

    def test_purpose_khong_hop_le_tra_422(self, client, ho_so_tot):
        assert client.post(DUONG_DAN, json={**ho_so_tot, "purpose": "gambling"}).status_code == 422

    def test_home_ownership_khong_hop_le_tra_422(self, client, ho_so_tot):
        assert client.post(DUONG_DAN, json={**ho_so_tot, "home_ownership": "SHARED"}).status_code == 422

    def test_tuoi_duoi_18_tra_422(self, client, ho_so_tot):
        assert client.post(DUONG_DAN, json={**ho_so_tot, "person_age": 16}).status_code == 422

    def test_thieu_truong_bat_buoc_tra_422(self, client):
        assert client.post(DUONG_DAN, json={"annual_inc": 100_000_000}).status_code == 422

    def test_khong_con_nhan_truong_cic_fico(self, client, ho_so_tot):
        """Gửi kèm cic_score/fico_score thì bị bỏ qua, không làm đổi kết quả."""
        chuan = client.post(DUONG_DAN, json=ho_so_tot).json()
        kem_cic = client.post(
            DUONG_DAN, json={**ho_so_tot, "cic_score": 750, "fico_score": 850}
        ).json()
        assert kem_cic["pd_probability"] == chuan["pd_probability"]
