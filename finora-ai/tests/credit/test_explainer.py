"""Test giải thích quyết định chấm điểm bằng TreeSHAP (C1.2).

Cùng cách làm với `tests/fraud/test_predictor.py`: dựng một gói model nhỏ trong thư
mục tạm thay vì dùng gói thật trong `models/credit/`. Test phải chạy được trên máy
vừa clone repo về, nơi `data/lc_clean.csv` không tồn tại (thư mục `data/` bị
gitignore) nên không ai huấn luyện lại được.

Bất biến quan trọng nhất được kiểm ở đây là **tính cộng dồn**: tổng mọi đóng góp
SHAP cộng với bias phải bằng đúng log-odds mà mô hình xuất ra. Nếu tính chất này
hỏng — thường vì lấy nhầm cột bias hoặc dựng ma trận đặc trưng khác với lúc chấm
điểm — thì giải thích vẫn trả về một danh sách trông rất hợp lý nhưng mô tả sai mô
hình, và không có gì báo lỗi.
"""

import numpy as np
import pandas as pd
import pytest
from fastapi.testclient import TestClient
from xgboost import XGBClassifier

from app.ml.credit.explainer import (
    CANH_BAO_LEAKAGE,
    NHOM_DAC_TRUNG,
    NHOM_LOAI_KHOI_TOM_TAT,
    SO_NHOM_TOI_DA,
    SO_YEU_TO_TOI_DA,
    _nhan,
    giai_thich_mo_hinh,
    tom_tat_yeu_to,
)
from app.ml.credit.features import FEATURE_NAMES, TARGET_COLS
from app.ml.credit.predictor import COT_DIEN_MEDIAN, BoDuDoan
from app.ml.shared.model_registry import luu_mo_hinh

PHIEN_BAN = "0.0.1-test"
DUONG_DAN = "/api/v1/ai/credit/explain"


def _tao_goi(tmp_path):
    """Dựng gói model tín dụng tối giản nhưng hợp lệ.

    Nhãn phụ thuộc `int_rate` để mô hình có tín hiệu thật để học — nhờ đó đóng góp
    TreeSHAP khác 0 và test giải thích mới có ý nghĩa. Chọn đúng `int_rate` vì nó
    cũng là đặc trưng leakage cần kiểm cảnh báo.
    """
    rng = np.random.default_rng(0)
    n = 400
    X = rng.random((n, len(FEATURE_NAMES))) * 100
    y = (X[:, FEATURE_NAMES.index("int_rate")] > 50).astype(int)

    model = XGBClassifier(
        n_estimators=10, max_depth=3, random_state=0, eval_metric="logloss", n_jobs=1
    )
    model.fit(X, y)

    luu_mo_hinh(
        model=model,
        version=PHIEN_BAN,
        metrics={"auc_roc": 0.7},
        feature_names=list(FEATURE_NAMES),
        model_dir=tmp_path,
        thong_so_bo_sung={
            "median_dien_thieu": {cot: 1.0 for cot in COT_DIEN_MEDIAN},
            "target_encodings": {cot: {} for cot in TARGET_COLS},
            "global_mean": 0.15,
        },
    )
    return tmp_path


def _ho_so(**ghi_de) -> dict:
    hs = {
        "person_age": 30,
        "emp_length": "5 years",
        "annual_inc": 300_000_000,
        "loan_amnt": 50_000_000,
        "home_ownership": "MORTGAGE",
        "purpose": "debt_consolidation",
        "verification_status": "Verified",
        "dti": 15.5,
        "installment": 4_500_000.0,
        "int_rate": 12.0,
        "term_months": 12,
    }
    hs.update(ghi_de)
    return hs


@pytest.fixture
def bo_du_doan(tmp_path):
    _tao_goi(tmp_path)
    return BoDuDoan.nap(PHIEN_BAN, tmp_path)


class TestTinhCongDon:
    """SHAP chỉ giải thích đúng khi các đóng góp cộng lại ra chính đầu ra mô hình."""

    def test_tong_dong_gop_cong_bias_bang_log_odds(self, bo_du_doan):
        import xgboost as xgb

        from app.ml.credit.explainer import _dong_gop_treeshap

        hs = _ho_so()
        dong_gop, _, bias = _dong_gop_treeshap(bo_du_doan, hs)

        # Log-odds thật do booster xuất ra, tính lại qua đúng đường chuẩn bị đặc trưng.
        from app.ml.credit.features import encode_features

        row = bo_du_doan.chuan_bi_dac_trung(hs)
        enc = encode_features(
            pd.DataFrame([row]), bo_du_doan.target_encodings, bo_du_doan.global_mean
        )
        X = enc[bo_du_doan.feature_names].values.astype(np.float64)
        dmat = xgb.DMatrix(X, feature_names=bo_du_doan.feature_names)
        log_odds = bo_du_doan.model.get_booster().predict(dmat, output_margin=True)[0]

        assert dong_gop.sum() + bias == pytest.approx(log_odds, abs=1e-4)

    def test_so_dong_gop_bang_so_dac_trung(self, bo_du_doan):
        """Cột bias phải bị cắt — thừa một cột nghĩa là lệch tên đặc trưng."""
        from app.ml.credit.explainer import _dong_gop_treeshap

        dong_gop, gia_tri, _ = _dong_gop_treeshap(bo_du_doan, _ho_so())
        assert len(dong_gop) == len(FEATURE_NAMES)
        assert len(gia_tri) == len(FEATURE_NAMES)


class TestGiaiThich:
    def test_tra_ve_du_khoa(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        assert set(kq) == {
            "yeu_to_bat_loi",
            "yeu_to_co_loi",
            "gia_tri_co_so",
            "canh_bao",
            "tom_tat",
        }

    def test_dau_dong_gop_dung_chieu(self, bo_du_doan):
        """Bất lợi phải là đóng góp dương, có lợi phải là âm."""
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        assert all(y["muc_dong_gop"] > 0 for y in kq["yeu_to_bat_loi"])
        assert all(y["muc_dong_gop"] < 0 for y in kq["yeu_to_co_loi"])

    def test_sap_xep_giam_dan_theo_do_lon(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        for chieu in ("yeu_to_bat_loi", "yeu_to_co_loi"):
            do_lon = [abs(y["muc_dong_gop"]) for y in kq[chieu]]
            assert do_lon == sorted(do_lon, reverse=True)

    def test_gioi_han_so_yeu_to_moi_chieu(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        assert len(kq["yeu_to_bat_loi"]) <= SO_YEU_TO_TOI_DA
        assert len(kq["yeu_to_co_loi"]) <= SO_YEU_TO_TOI_DA

    def test_moi_yeu_to_co_nhan_tieng_viet(self, bo_du_doan):
        """Tên cột thô như `log_du_no` vô nghĩa với người đọc — phải có nhãn."""
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        for y in (*kq["yeu_to_bat_loi"], *kq["yeu_to_co_loi"]):
            assert y["mo_ta"]
            assert y["mo_ta"] != y["dac_trung"]

    def test_khong_co_dong_gop_bang_khong(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        for y in (*kq["yeu_to_bat_loi"], *kq["yeu_to_co_loi"]):
            assert y["muc_dong_gop"] != 0.0


class TestCanhBaoLeakage:
    """int_rate được hiển thị nhưng phải gắn cờ, không được im lặng."""

    def test_int_rate_bi_danh_dau_leakage(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        tat_ca = [*kq["yeu_to_bat_loi"], *kq["yeu_to_co_loi"]]
        int_rate = [y for y in tat_ca if y["dac_trung"] == "int_rate"]

        # Gói test học nhãn từ chính int_rate nên nó chắc chắn phải xuất hiện.
        assert int_rate, "int_rate phải nằm trong giải thích của gói test"
        assert int_rate[0]["la_leakage"] is True

    def test_co_canh_bao_khi_leakage_xuat_hien(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        assert CANH_BAO_LEAKAGE in kq["canh_bao"]

    def test_dac_trung_thuong_khong_bi_danh_dau(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())
        tat_ca = [*kq["yeu_to_bat_loi"], *kq["yeu_to_co_loi"]]
        for y in tat_ca:
            if y["dac_trung"] != "int_rate":
                assert y["la_leakage"] is False


class TestNhan:
    def test_nhan_co_san(self):
        assert _nhan("cic_score") == "Điểm tín dụng CIC"

    def test_nhan_chi_bao_thieu_dung_nhan_cot_goc(self):
        assert _nhan("cic_score_missing") == "Thiếu dữ liệu: Điểm tín dụng CIC"

    def test_moi_dac_trung_deu_co_nhan(self):
        """Bộ 47 đặc trưng không được có cột nào rơi về tên thô."""
        for ten in FEATURE_NAMES:
            assert _nhan(ten) != ten, f"{ten} chưa có nhãn tiếng Việt"


def _dong_gop(**theo_cot: float) -> np.ndarray:
    """Vector đóng góp SHAP giả: 0 ở mọi cột trừ những cột được đặt tên."""
    v = np.zeros(len(FEATURE_NAMES))
    for cot, muc in theo_cot.items():
        v[FEATURE_NAMES.index(cot)] = muc
    return v


class TestTomTat:
    """Bản gộp cho thẩm định viên: mỗi dữ kiện gốc một dòng, không số log-odds.

    Cùng một dữ kiện (dư nợ, thu nhập, lãi suất) đi vào mô hình dưới nhiều dạng
    (thô, log, tỷ lệ). SHAP chia đóng góp ra từng dạng, thường trái dấu nhau, nên
    bản thô không trả lời được "dư nợ là tốt hay xấu". Tính cộng dồn của SHAP cho
    phép cộng lại theo nhóm mà vẫn đúng.
    """

    def test_moi_dac_trung_deu_thuoc_mot_nhom(self):
        for ten in FEATURE_NAMES:
            assert ten in NHOM_DAC_TRUNG, f"{ten} chưa được xếp nhóm"

    def test_gop_dac_trung_dan_xuat_ve_mot_dong(self):
        """Dư nợ thô +0.16 và dư nợ log -0.15 phải thành một dòng +0.01."""
        kq = tom_tat_yeu_to(_dong_gop(tong_du_no=0.16, log_du_no=-0.15), FEATURE_NAMES)
        assert kq["co_loi"] == []
        assert [y["ma_nhom"] for y in kq["bat_loi"]] == ["du_no"]
        assert kq["bat_loi"][0]["muc_dong_gop"] == pytest.approx(0.01)

    def test_nhom_lai_suat_bi_loai(self):
        """int_rate là leakage; phương pháp tính lãi suy ra từ nó nên đi cùng."""
        assert "lai_suat" in NHOM_LOAI_KHOI_TOM_TAT
        for cot in ("int_rate", "interest_method_encoded", "int_rate_missing"):
            assert NHOM_DAC_TRUNG[cot][0] == "lai_suat"

        kq = tom_tat_yeu_to(_dong_gop(int_rate=0.9, dti=0.1), FEATURE_NAMES)
        assert [y["ma_nhom"] for y in kq["bat_loi"]] == ["dti"]

    def test_ty_le_tra_no_thang_khong_bi_loai(self):
        """`ty_le_tra_no_thang` KHÔNG mang leakage nên phải hiển thị được.

        Nó tính từ `installment` và `annual_inc` — dữ liệu hồ sơ thật — chứ không
        suy ra từ `int_rate`, nên thuộc nhóm tiền trả hàng tháng.
        """
        assert NHOM_DAC_TRUNG["ty_le_tra_no_thang"][0] == "tra_hang_thang"

        kq = tom_tat_yeu_to(_dong_gop(ty_le_tra_no_thang=0.9, dti=0.1), FEATURE_NAMES)
        assert [y["ma_nhom"] for y in kq["bat_loi"]] == ["tra_hang_thang", "dti"]

    def test_toi_da_ba_nhom_moi_chieu(self):
        assert SO_NHOM_TOI_DA == 3
        kq = tom_tat_yeu_to(
            _dong_gop(dti=0.5, installment=0.4, cic_score=0.3, annual_inc=0.2, loan_amnt=0.1),
            FEATURE_NAMES,
        )
        assert [y["ma_nhom"] for y in kq["bat_loi"]] == ["dti", "tra_hang_thang", "diem_cic"]

    def test_muc_do_so_voi_nhom_manh_nhat(self):
        """Số liệu đúng như ảnh chụp: CIC -0.32 là mốc, DTI 0.18 và trả góp 0.12 là vừa."""
        kq = tom_tat_yeu_to(
            _dong_gop(cic_score=-0.32, dti=0.18, installment=0.12, term_months=0.02),
            FEATURE_NAMES,
        )
        muc_do = {y["ma_nhom"]: y["muc_do"] for y in (*kq["bat_loi"], *kq["co_loi"])}
        assert muc_do == {"diem_cic": "manh", "dti": "vua", "tra_hang_thang": "vua", "ky_han": "nhe"}

    def test_bo_qua_nhom_khong_anh_huong(self):
        kq = tom_tat_yeu_to(_dong_gop(tong_du_no=0.1, log_du_no=-0.1), FEATURE_NAMES)
        assert kq == {"bat_loi": [], "co_loi": []}

    def test_dong_gop_mo_ta_va_sap_xep(self, bo_du_doan):
        kq = giai_thich_mo_hinh(bo_du_doan, _ho_so())["tom_tat"]
        for chieu in ("bat_loi", "co_loi"):
            assert len(kq[chieu]) <= SO_NHOM_TOI_DA
            do_lon = [abs(y["muc_dong_gop"]) for y in kq[chieu]]
            assert do_lon == sorted(do_lon, reverse=True)
            for y in kq[chieu]:
                assert set(y) == {"ma_nhom", "mo_ta", "muc_do", "muc_dong_gop"}
                assert y["muc_do"] in {"manh", "vua", "nhe"}
                assert y["ma_nhom"] != "lai_suat"


class TestEndpointExplain:
    @pytest.fixture
    def client(self, monkeypatch, bo_du_doan):
        import main
        from app.api import credit_router

        credit_router.lay_bo_du_doan.cache_clear()
        credit_router.lay_cic_client.cache_clear()
        monkeypatch.setattr(credit_router, "lay_bo_du_doan", lambda: bo_du_doan)

        # Không gọi cache_clear() sau yield: monkeypatch đã thay `lay_bo_du_doan`
        # bằng lambda (không có cache_clear) và tự khôi phục bản gốc khi test xong.
        yield TestClient(main.app)

    def test_tra_200_va_du_hai_nua_giai_thich(self, client):
        r = client.post(DUONG_DAN, json=_ho_so())
        assert r.status_code == 200

        body = r.json()
        # Nửa ML và nửa quy tắc — thiếu một trong hai thì không giải thích được
        # evaluation_score vì điểm này trộn cả hai nguồn.
        assert (
            body["giai_thich_mo_hinh"]["yeu_to_bat_loi"]
            or body["giai_thich_mo_hinh"]["yeu_to_co_loi"]
        )
        assert body["rule_trace"]
        assert body["model_version"] == PHIEN_BAN
        assert body["decision_policy_version"] == "CREDIT_POLICY_V1"

    def test_tra_ve_ban_gop_cho_tham_dinh_vien(self, client):
        body = client.post(DUONG_DAN, json=_ho_so()).json()
        tom_tat = body["giai_thich_mo_hinh"]["tom_tat"]
        assert set(tom_tat) == {"bat_loi", "co_loi"}
        assert all("lai_suat" != y["ma_nhom"] for y in (*tom_tat["bat_loi"], *tom_tat["co_loi"]))

    def test_cham_lai_cung_ho_so_cho_ket_qua_giong_het(self, client):
        """Cùng hồ sơ phải ra cùng quyết định ở mọi lần gọi.

        Bắt lỗi lẫn trạng thái giữa các request: cache config bị sửa tại chỗ, hay
        bộ dự đoán giữ lại dữ liệu hồ sơ trước, thì hai lần gọi sẽ lệch.
        """
        hs = _ho_so()
        lan_1 = client.post(DUONG_DAN, json=hs).json()
        lan_2 = client.post(DUONG_DAN, json=hs).json()

        for khoa in (
            "pd_probability",
            "risk_score",
            "evaluation_score",
            "credit_grade",
            "decision",
        ):
            assert lan_1[khoa] == lan_2[khoa], f"{khoa} lệch giữa hai lần gọi"

    def test_thieu_truong_bat_buoc_tra_422(self, client):
        r = client.post(DUONG_DAN, json={"annual_inc": 100_000_000})
        assert r.status_code == 422

    def test_khong_nap_duoc_goi_tra_503(self, monkeypatch):
        import main
        from app.api import credit_router

        def _no_():
            raise FileNotFoundError("không có gói model")

        credit_router.lay_bo_du_doan.cache_clear()
        monkeypatch.setattr(credit_router, "lay_bo_du_doan", _no_)

        r = TestClient(main.app).post(DUONG_DAN, json=_ho_so())
        assert r.status_code == 503
        assert r.json()["detail"]["code"] == "MODEL_NOT_AVAILABLE"
