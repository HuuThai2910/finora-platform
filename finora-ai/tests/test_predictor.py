import json

import numpy as np
import pytest
from sklearn.linear_model import LogisticRegression

from app.ml.features import FEATURE_NAMES
from app.ml.model_registry import luu_mo_hinh
from app.ml.predictor import COT_DIEN_MEDIAN, BoDuDoan

# Median cố tình đặt giá trị "không thể nhầm với con số nào khác": nếu test thấy đúng
# các số dưới đây thì chắc chắn giá trị đến từ gói model, không phải hằng số trong code.
MEDIAN_GIA = {
    "person_age": 42.0,
    "emp_length_years": 6.0,
    "annual_inc": 1_625_000_000.0,
    "loan_amnt": 325_000_000.0,
}


@pytest.fixture
def ho_so_day_du():
    """Hồ sơ khai đủ 6 trường mà FINORA thu thập được. Đơn vị VNĐ."""
    return {
        "person_age": 30,
        "emp_length": "5 years",
        "annual_inc": 300_000_000,
        "loan_amnt": 50_000_000,
        "home_ownership": "MORTGAGE",
        "purpose": "debt_consolidation",
    }


@pytest.fixture
def thu_muc_goi(tmp_path):
    return tmp_path / "models"


def _tao_goi(thu_muc, version="8.0.0", feature_names=None, median=None):
    """Tạo một gói model hợp lệ để test, không cần chạy huấn luyện thật."""
    feature_names = feature_names if feature_names is not None else FEATURE_NAMES
    median = median if median is not None else MEDIAN_GIA

    # Sinh dữ liệu huấn luyện THEO ĐÚNG THANG ĐO của đặc trưng thật: `annual_inc` cỡ
    # 10^9 VNĐ, one-hot cỡ 1. Nếu fit trên randn(0,1) thuần thì hệ số hồi quy lệch
    # thang ~9 bậc, sigmoid bão hòa và predict_proba trả đúng 1.0 — hỏng test mà
    # không phản ánh lỗi nào trong predictor.
    rng = np.random.RandomState(42)
    thang_do = np.array([abs(median.get(c, 1.0)) or 1.0 for c in feature_names])
    X = rng.randn(200, len(feature_names)) * thang_do
    y = (X[:, 0] / thang_do[0] + rng.randn(200) * 0.5 > 0).astype(int)
    model = LogisticRegression(max_iter=300).fit(X, y)

    luu_mo_hinh(
        model,
        version,
        {"auc_roc": 0.60, "recall": 0.60},
        feature_names,
        thu_muc,
        thong_so_bo_sung={"median_dien_thieu": median},
    )
    return thu_muc


class TestBoDacTrung:
    def test_khong_con_dac_trung_nao_tu_cic_hoac_fico(self):
        """FINORA không có API tới CIC — đặc trưng nào cần dữ liệu đó đều không lấy được."""
        cam = {
            "fico_score", "fico_bucket", "dti", "credit_hist_years", "delinq_2yrs",
            "mths_since_last_delinq", "acc_now_delinq", "revol_bal", "revol_util",
            "revol_risk", "open_acc", "tot_cur_bal", "mort_acc", "inq_last_6mths",
        }
        con_sot = cam & set(FEATURE_NAMES)
        assert not con_sot, f"Còn đặc trưng phụ thuộc CIC/FICO: {sorted(con_sot)}"

    def test_dung_21_dac_trung(self):
        assert len(FEATURE_NAMES) == 21
        assert len(COT_DIEN_MEDIAN) == 4


class TestNap:
    def test_nap_thanh_cong(self, thu_muc_goi):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        assert bo.feature_names == FEATURE_NAMES
        assert bo.median == MEDIAN_GIA

    def test_chan_goi_cu_khong_co_median(self, thu_muc_goi):
        """Gói đời cũ không có median_dien_thieu — phải từ chối, không chạy tiếp."""
        rng = np.random.RandomState(0)
        X, y = rng.randn(100, len(FEATURE_NAMES)), rng.randint(0, 2, 100)
        luu_mo_hinh(
            LogisticRegression(max_iter=200).fit(X, y),
            "6.0.0", {"auc_roc": 0.67}, FEATURE_NAMES, thu_muc_goi,
        )
        with pytest.raises(ValueError, match="median_dien_thieu"):
            BoDuDoan.nap("6.0.0", thu_muc_goi)

    def test_chan_khi_feature_names_lech(self, thu_muc_goi):
        """features.py đổi sau khi model đã train → cột thứ i không còn đúng nghĩa."""
        _tao_goi(thu_muc_goi, feature_names=FEATURE_NAMES[:-1])
        with pytest.raises(ValueError, match="bộ đặc trưng khác"):
            BoDuDoan.nap("8.0.0", thu_muc_goi)

    def test_chan_khi_sha256_lech(self, thu_muc_goi):
        """File .pkl bị thay sau khi lưu → metadata không còn mô tả đúng model."""
        _tao_goi(thu_muc_goi)
        meta_path = thu_muc_goi / "model_v8.0.0.json"
        meta = json.loads(meta_path.read_text())
        meta["sha256"] = "0" * 64
        meta_path.write_text(json.dumps(meta))
        with pytest.raises(ValueError, match="SHA-256"):
            BoDuDoan.nap("8.0.0", thu_muc_goi)


class TestChuanBiDacTrung:
    def test_giu_nguyen_gia_tri_da_khai(self, thu_muc_goi, ho_so_day_du):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        row = bo.chuan_bi_dac_trung(ho_so_day_du)
        assert row["person_age"] == 30
        assert row["annual_inc"] == 300_000_000
        assert row["emp_length_years"] == 5.0

    def test_truong_thieu_dien_bang_median_trong_goi(self, thu_muc_goi):
        """Hồi quy cho lỗi train/serve skew — xem docstring predictor.py."""
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        row = bo.chuan_bi_dac_trung({
            "annual_inc": 300_000_000, "loan_amnt": 50_000_000,
            "home_ownership": "RENT", "purpose": "education",
        })
        assert row["person_age"] == MEDIAN_GIA["person_age"] == 42.0
        assert row["emp_length_years"] == MEDIAN_GIA["emp_length_years"] == 6.0

    def test_dien_du_4_cot_goc(self, thu_muc_goi):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        row = bo.chuan_bi_dac_trung({"home_ownership": "RENT", "purpose": "car"})
        for cot in COT_DIEN_MEDIAN:
            assert row[cot] is not None, f"{cot} chưa được điền"

    def test_emp_length_dang_chuoi_duoc_doc_dung(self, thu_muc_goi, ho_so_day_du):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        assert bo.chuan_bi_dac_trung({**ho_so_day_du, "emp_length": "10+ years"})["emp_length_years"] == 10.0
        assert bo.chuan_bi_dac_trung({**ho_so_day_du, "emp_length": "< 1 year"})["emp_length_years"] == 0.5


class TestDuDoan:
    def test_pd_trong_khoang_hop_le(self, thu_muc_goi, ho_so_day_du):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        assert 0.0 < bo.du_doan_pd(ho_so_day_du) < 1.0

    def test_goi_hai_lan_cho_ket_qua_y_het(self, thu_muc_goi, ho_so_day_du):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        assert bo.du_doan_pd(ho_so_day_du) == bo.du_doan_pd(ho_so_day_du)

    def test_tra_ve_du_khoa(self, thu_muc_goi, ho_so_day_du):
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        kq = bo.du_doan(ho_so_day_du)
        bat_buoc = {
            "pd_probability", "risk_score", "evaluation_score", "credit_grade",
            "suggested_limit", "suggested_rate", "decision", "model_version",
        }
        assert bat_buoc.issubset(kq.keys())
        assert kq["credit_grade"] in ("A", "B", "C", "D")
        assert kq["decision"] in ("APPROVED", "PENDING_REVIEW", "REJECTED")
        assert kq["model_version"] == "8.0.0"

    def test_lai_suat_khong_vuot_tran_20_phan_tram(self, thu_muc_goi, ho_so_day_du):
        """Điều 468 Bộ luật Dân sự 2015 — trần lãi suất thỏa thuận 20%/năm."""
        _tao_goi(thu_muc_goi)
        bo = BoDuDoan.nap("8.0.0", thu_muc_goi)
        assert bo.du_doan(ho_so_day_du)["suggested_rate"] <= 0.20


class TestRuleEngineKhongDungCIC:
    """Rule engine thiết kế lại: 4 yếu tố đều tính từ dữ liệu tự khai + eKYC."""

    def test_ho_so_ly_tuong_dat_100_diem(self):
        from app.services.rule_engine import tinh_diem_rui_ro
        assert tinh_diem_rui_ro({
            "annual_inc": 500_000_000,   # ≥300tr        → 25
            "loan_amnt": 50_000_000,     # tỷ lệ 0,1     → 25
            "emp_length_years": 8,       # ≥5 năm        → 25
            "home_ownership": "OWN",     # sở hữu nhà    → 25
        }) == 100

    def test_ho_so_xau_nhat_van_co_san_20_diem(self):
        from app.services.rule_engine import tinh_diem_rui_ro
        assert tinh_diem_rui_ro({
            "annual_inc": 50_000_000, "loan_amnt": 50_000_000,
            "emp_length_years": 0.5, "home_ownership": "OTHER",
        }) == 20

    def test_ho_so_rong_khong_crash(self):
        """Thiếu hết dữ liệu → điểm sàn, không được ném lỗi chia cho 0."""
        from app.services.rule_engine import tinh_diem_rui_ro
        assert tinh_diem_rui_ro({}) == 20
