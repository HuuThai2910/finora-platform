"""Test phần diễn giải kết quả chấm điểm sang ngôn ngữ người vay (C1.2).

Bất biến quan trọng nhất ở đây là gợi ý phải bám NGƯỠNG THẬT trong Rule Engine.
Nếu ai đó viết cứng con số vào câu gợi ý, test `test_moc_goi_y_theo_cau_hinh_that`
sẽ gãy ngay — vì gợi ý lúc đó không đổi theo cấu hình nữa.

Luật do admin tự tạo cũng phải sinh được gợi ý: có mẫu câu thì dùng mẫu, không
có thì ghép câu chung từ mô tả luật và chiều so sánh.
"""

import json

import pytest

from app.ml.credit.dien_giai import (
    DIEN_GIAI_CHOT_CHAN,
    SO_GOI_Y_TOI_DA,
    sinh_dien_giai,
)
from app.services.credit import product_config


def _vet(ma, mo_ta, truong, gia_tri, diem, toi_da=20, thieu=False, trong_so=1.0):
    return {
        "ma": ma,
        "mo_ta": mo_ta,
        "truong": truong,
        "gia_tri": gia_tri,
        "diem": diem,
        "toi_da": toi_da,
        "trong_so": trong_so,
        "thieu_du_lieu": thieu,
    }


def _ket_qua(**ghi_de) -> dict:
    kq = {
        "decision": "REJECTED",
        "credit_grade": "C",
        "suggested_limit": 40_000_000,
        "rejection_reasons": [],
        "rule_trace": [
            _vet("CHARACTER_CIC_HISTORY", "Điểm tín dụng CIC", "cic_score", 680.0, 10),
            _vet("CAPACITY_EXISTING_DEBT", "Tỷ lệ nợ trên thu nhập", "dti", 25.0, 8),
            _vet(
                "CAPITAL_RESIDENCE_STABILITY",
                "Tình trạng nhà ở",
                "home_ownership",
                "RENT",
                8,
            ),
        ],
    }
    kq.update(ghi_de)
    return kq


@pytest.fixture
def sua_config(tmp_path, monkeypatch):
    """Cho phép thêm luật trong test mà không đụng file thật."""
    duong_dan_goc = product_config._CONFIG_PATH
    goc = json.loads(duong_dan_goc.read_text(encoding="utf-8"))
    file_tam = tmp_path / "product_config.json"
    monkeypatch.setattr(product_config, "_CONFIG_PATH", file_tam)

    def ghi(sua):
        cau_hinh = json.loads(json.dumps(goc))
        sua(cau_hinh)
        file_tam.write_text(json.dumps(cau_hinh, ensure_ascii=False), encoding="utf-8")
        product_config.reload()

    ghi(lambda c: None)
    yield ghi
    monkeypatch.setattr(product_config, "_CONFIG_PATH", duong_dan_goc)
    product_config.reload()


class TestThongDiep:
    def test_duyet_neu_ra_han_muc(self):
        d = sinh_dien_giai(
            _ket_qua(decision="APPROVED", credit_grade="A", suggested_limit=100_000_000)
        )
        assert "đủ điều kiện" in d["thong_diep"]
        assert "100.000.000" in d["thong_diep"]

    def test_cho_tham_dinh_noi_ro_ly_do(self):
        d = sinh_dien_giai(_ket_qua(decision="PENDING_REVIEW"))
        assert "thẩm định viên" in d["thong_diep"]

    def test_tu_choi_do_chot_chan_khac_voi_do_diem_thap(self):
        chot = sinh_dien_giai(_ket_qua(rejection_reasons=["CIC_BAD_DEBT_GROUP"]))
        diem = sinh_dien_giai(_ket_qua())
        assert "quy định bắt buộc" in chot["thong_diep"]
        assert "chưa đạt ngưỡng" in diem["thong_diep"]


class TestLyDo:
    def test_moi_ma_chot_chan_deu_co_cau_giai_thich(self):
        """Người vay không hiểu được mã kỹ thuật — mã nào cũng phải có bản dịch."""
        for ma in DIEN_GIAI_CHOT_CHAN:
            d = sinh_dien_giai(_ket_qua(rejection_reasons=[ma]))
            assert d["ly_do_chinh"], f"{ma} không sinh được lý do"
            assert ma not in d["ly_do_chinh"][0], f"{ma} bị rò mã kỹ thuật ra ngoài"

    def test_thieu_du_lieu_noi_ro_la_su_co_ha_tang(self):
        vet = [
            _vet(
                "CHARACTER_CIC_HISTORY",
                "Điểm tín dụng CIC",
                "cic_score",
                None,
                8,
                thieu=True,
            )
        ]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert any("Chưa tra được" in x for x in d["ly_do_chinh"])


class TestGoiY:
    def test_moc_goi_y_theo_cau_hinh_that(self):
        """Gợi ý phải nêu đúng ngưỡng kế tiếp trong config, không phải số viết cứng.

        DTI đang 25 nằm ở bậc (20, 15] nên mốc cần đạt gần nhất là 20, không phải
        bậc tốt nhất 10.
        """
        d = sinh_dien_giai(_ket_qua())
        goi_y_dti = [x for x in d["goi_y_cai_thien"] if "nợ trên thu nhập" in x]
        assert goi_y_dti, "thiếu gợi ý cho DTI"
        assert "20%" in goi_y_dti[0]

    def test_gioi_han_so_goi_y(self):
        d = sinh_dien_giai(_ket_qua())
        assert len(d["goi_y_cai_thien"]) <= SO_GOI_Y_TOI_DA

    def test_uu_tien_luat_mat_nhieu_diem_nhat(self):
        """Luật hụt 12 điểm phải được gợi ý trước luật hụt 10 điểm."""
        d = sinh_dien_giai(_ket_qua())
        assert "nợ trên thu nhập" in d["goi_y_cai_thien"][0]

    def test_luat_dat_diem_toi_da_khong_sinh_goi_y(self):
        vet = [
            _vet(
                "CHARACTER_CREDIT_SEEKING", "Số lần tra cứu", "so_lan_tra_cuu", 0.0, 20
            )
        ]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert d["goi_y_cai_thien"] == []

    def test_khong_ro_ri_ky_hieu_dinh_dang(self):
        """Chuỗi mẫu dùng {moc}; quên format sẽ để lộ dấu ngoặc ra người dùng."""
        d = sinh_dien_giai(_ket_qua())
        for x in d["goi_y_cai_thien"]:
            assert "{" not in x and "}" not in x

    def test_luat_khong_con_trong_config_bi_bo_qua(self):
        """Trace cũ (snapshot) có thể nhắc luật admin đã xoá — không được crash."""
        vet = [_vet("LUAT_DA_XOA", "Luật cũ", "dti", 25.0, 8)]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert d["goi_y_cai_thien"] == []


# Luật mẫu do admin tự tạo, không có sẵn trong config mặc định.
LUAT_TUOI = {
    "ma": "AGE_BRACKET",
    "mo_ta": "Tuổi người vay",
    "truong": "person_age",
    "nghich_dao": False,
    "trong_so": 1.0,
    "bat": True,
    "diem_khi_thieu": 10,
    "bac": [[30, 20], [25, 12], [0, 5]],
}


class TestGoiYLuatTuTao:
    def test_dung_mau_cau_admin_nhap(self, sua_config):
        sua_config(
            lambda c: c["rules"].append(
                {**LUAT_TUOI, "goi_y": "Hồ sơ từ {moc} tuổi được đánh giá ổn định hơn."}
            )
        )
        vet = [_vet("AGE_BRACKET", "Tuổi người vay", "person_age", 22.0, 5)]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert d["goi_y_cai_thien"] == ["Hồ sơ từ 25 tuổi được đánh giá ổn định hơn."]

    def test_khong_co_mau_thi_ghep_cau_chung_luat_thuan(self, sua_config):
        sua_config(lambda c: c["rules"].append(LUAT_TUOI))
        vet = [_vet("AGE_BRACKET", "Tuổi người vay", "person_age", 22.0, 5)]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert len(d["goi_y_cai_thien"]) == 1
        cau = d["goi_y_cai_thien"][0]
        assert "Tuổi người vay" in cau and "25" in cau and "tuổi" in cau

    def test_khong_co_mau_luat_nghich_dao(self, sua_config):
        luat = {
            "ma": "DEBT_LOAD",
            "mo_ta": "Tổng dư nợ hiện có",
            "truong": "tong_du_no",
            "nghich_dao": True,
            "trong_so": 1.0,
            "bat": True,
            "diem_khi_thieu": 10,
            "bac": [[50_000_000, 20], [150_000_000, 10], [1e9, 0]],
        }
        sua_config(lambda c: c["rules"].append(luat))
        vet = [_vet("DEBT_LOAD", "Tổng dư nợ hiện có", "tong_du_no", 90_000_000.0, 10)]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        cau = d["goi_y_cai_thien"][0]
        assert "Giảm" in cau and "50.000.000" in cau and "đ" in cau

    def test_truong_ty_le_nhan_100_trong_cau_chung(self, sua_config):
        luat = {
            "ma": "LOAN_TO_INCOME",
            "mo_ta": "Khoản vay so với thu nhập năm",
            "truong": "loan_to_income",
            "nghich_dao": True,
            "trong_so": 1.0,
            "bat": True,
            "diem_khi_thieu": 10,
            "bac": [[0.2, 20], [0.5, 10], [1e9, 0]],
        }
        sua_config(lambda c: c["rules"].append(luat))
        vet = [
            _vet(
                "LOAN_TO_INCOME",
                "Khoản vay so với thu nhập năm",
                "loan_to_income",
                0.35,
                10,
            )
        ]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert "20%" in d["goi_y_cai_thien"][0]

    def test_luat_phan_loai_khong_co_mau_van_co_cau(self, sua_config):
        luat = {
            "ma": "PURPOSE_RISK",
            "mo_ta": "Mục đích vay",
            "truong": "purpose",
            "trong_so": 1.0,
            "bat": True,
            "diem_khi_thieu": 10,
            "bang_diem": {
                "debt_consolidation": 5,
                "credit_card": 8,
                "home_improvement": 20,
                "major_purchase": 15,
                "medical": 12,
                "car": 15,
                "small_business": 4,
                "moving": 10,
                "vacation": 6,
                "education": 18,
                "other": 8,
            },
        }
        sua_config(lambda c: c["rules"].append(luat))
        vet = [_vet("PURPOSE_RISK", "Mục đích vay", "purpose", "vacation", 6)]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert (
            len(d["goi_y_cai_thien"]) == 1 and "Mục đích vay" in d["goi_y_cai_thien"][0]
        )

    def test_goi_y_theo_shap_tim_duoc_luat_tu_tao_qua_truong(self, sua_config):
        """Nhóm SHAP `tuoi` phải ánh xạ sang luật đọc `person_age` dù luật mới toanh."""
        sua_config(lambda c: c["rules"].append(LUAT_TUOI))
        vet = [_vet("AGE_BRACKET", "Tuổi người vay", "person_age", 22.0, 5)]
        tom_tat = {
            "bat_loi": [
                {
                    "ma_nhom": "tuoi",
                    "mo_ta": "Tuổi",
                    "muc_do": "manh",
                    "muc_dong_gop": 0.5,
                }
            ]
        }
        d = sinh_dien_giai(_ket_qua(rule_trace=vet), tom_tat)
        assert d["goi_y_cai_thien"] and "25" in d["goi_y_cai_thien"][0]


class TestKhongLoSoKyThuat:
    def test_khong_co_log_odds_trong_ban_dien_giai(self):
        """Bản cho người vay không được chứa số SHAP hay thuật ngữ kỹ thuật."""
        d = sinh_dien_giai(_ket_qua(rejection_reasons=["CIC_BAD_DEBT_GROUP"]))
        toan_bo = " ".join([d["thong_diep"], *d["ly_do_chinh"], *d["goi_y_cai_thien"]])
        for tu in ("log-odds", "SHAP", "pd_probability", "muc_dong_gop"):
            assert tu not in toan_bo


@pytest.mark.parametrize("quyet_dinh", ["APPROVED", "PENDING_REVIEW", "REJECTED"])
def test_luon_tra_du_ba_khoa(quyet_dinh):
    d = sinh_dien_giai(_ket_qua(decision=quyet_dinh))
    assert set(d) == {"thong_diep", "ly_do_chinh", "goi_y_cai_thien"}
