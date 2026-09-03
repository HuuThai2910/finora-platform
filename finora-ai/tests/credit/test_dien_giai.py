"""Test phần diễn giải kết quả chấm điểm sang ngôn ngữ người vay (C1.2).

Bất biến quan trọng nhất ở đây là gợi ý phải bám NGƯỠNG THẬT trong Rule Engine.
Nếu ai đó viết cứng con số vào câu gợi ý, test `test_moc_goi_y_theo_cau_hinh_that`
sẽ gãy ngay — vì gợi ý lúc đó không đổi theo cấu hình nữa.
"""

import pytest

from app.ml.credit.dien_giai import (
    DIEN_GIAI_CHOT_CHAN,
    SO_GOI_Y_TOI_DA,
    sinh_dien_giai,
)


def _ket_qua(**ghi_de) -> dict:
    kq = {
        "decision": "REJECTED",
        "credit_grade": "C",
        "suggested_limit": 40_000_000,
        "rejection_reasons": [],
        "rule_trace": [
            {"ma": "CHARACTER_CIC_HISTORY", "nhom_5c": "Character",
             "mo_ta": "Điểm tín dụng CIC", "gia_tri": 680.0,
             "diem": 10, "toi_da": 20, "thieu_du_lieu": False},
            {"ma": "CAPACITY_EXISTING_DEBT", "nhom_5c": "Capacity",
             "mo_ta": "Tỷ lệ nợ trên thu nhập", "gia_tri": 25.0,
             "diem": 8, "toi_da": 20, "thieu_du_lieu": False},
            {"ma": "CAPITAL_RESIDENCE_STABILITY", "nhom_5c": "Capital",
             "mo_ta": "Tình trạng nhà ở", "gia_tri": "RENT",
             "diem": 8, "toi_da": 20, "thieu_du_lieu": False},
        ],
    }
    kq.update(ghi_de)
    return kq


class TestThongDiep:
    def test_duyet_neu_ra_han_muc(self):
        d = sinh_dien_giai(_ket_qua(decision="APPROVED", credit_grade="A",
                                    suggested_limit=100_000_000))
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
        vet = [{"ma": "CHARACTER_CIC_HISTORY", "nhom_5c": "Character",
                "mo_ta": "Điểm tín dụng CIC", "gia_tri": None,
                "diem": 8, "toi_da": 20, "thieu_du_lieu": True}]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert any("Chưa tra được" in x for x in d["ly_do_chinh"])


class TestGoiY:
    def test_moc_goi_y_theo_cau_hinh_that(self):
        """Gợi ý phải nêu đúng ngưỡng kế tiếp trong BO_LUAT, không phải số viết cứng.

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
        vet = [{"ma": "CHARACTER_CREDIT_SEEKING", "nhom_5c": "Character",
                "mo_ta": "Số lần tra cứu", "gia_tri": 0.0,
                "diem": 20, "toi_da": 20, "thieu_du_lieu": False}]
        d = sinh_dien_giai(_ket_qua(rule_trace=vet))
        assert d["goi_y_cai_thien"] == []

    def test_khong_ro_ri_ky_hieu_dinh_dang(self):
        """Chuỗi mẫu dùng {moc}; quên format sẽ để lộ dấu ngoặc ra người dùng."""
        d = sinh_dien_giai(_ket_qua())
        for x in d["goi_y_cai_thien"]:
            assert "{" not in x and "}" not in x


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
