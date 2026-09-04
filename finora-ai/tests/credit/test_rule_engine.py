"""Test rule engine: từng luật, chấm điểm tổng, vết luật, chốt chặn và quyết định.

Đây là tầng quyết định duyệt hay từ chối tiền thật, nên test tập trung vào những
sai lầm KHÔNG gây crash: điểm ra ngoài thang, hồ sơ rỗng lọt qua, vi phạm pháp lý
bị bỏ sót, vết luật không khớp điểm.

Luật là dữ liệu trong `product_config.json`, không còn khai trong code. Test về
từng luật dưới đây khẳng định CẤU HÌNH MẶC ĐỊNH — bộ luật đã kiểm chứng AUC 0,64
trên 150.000 hồ sơ — để việc sửa config ngoài ý muốn bị phát hiện.
"""

import json

import pytest

from app.services.credit import product_config
from app.services.credit.rule_engine import (
    TY_LE_LUAT_TOI_THIEU_CO_DU_LIEU,
    LuatChamDiem,
    cham_diem_chi_tiet,
    dem_luat_co_du_lieu,
    kiem_tra_chot_chan_cung,
    lay_bo_luat,
    quyet_dinh,
    so_luat_toi_thieu_co_du_lieu,
    tinh_diem_rui_ro,
    tinh_diem_tong_hop,
    xep_hang,
)

# Bảng điểm nhà ở mặc định trong config — test khẳng định giá trị này để việc
# sửa config ngoài ý muốn bị phát hiện, thay vì đọc lại chính config đang test.
DIEM_NHA_O_MAC_DINH = {"OWN": 20, "MORTGAGE": 16, "RENT": 8, "OTHER": 4}

# Năm luật mặc định, đúng thứ tự trong config.
MA_LUAT_MAC_DINH = (
    "CHARACTER_CIC_HISTORY",
    "CAPACITY_EXISTING_DEBT",
    "CAPACITY_INSTALLMENT_BURDEN",
    "CHARACTER_CREDIT_SEEKING",
    "CAPITAL_RESIDENCE_STABILITY",
)


def _luat(ma: str) -> LuatChamDiem:
    return next(l for l in lay_bo_luat() if l.ma == ma)


def _luat_config(cau_hinh: dict, ma: str) -> dict:
    return next(r for r in cau_hinh["rules"] if r["ma"] == ma)


HO_SO_TOT = {
    "cic_score": 780,
    "dti": 8.0,
    "annual_inc": 360_000_000,
    "installment": 2_000_000,
    "so_lan_tra_cuu": 0,
    "home_ownership": "OWN",
    "loan_amnt": 50_000_000,
    "term_months": 12,
    "int_rate": 12.0,
    "person_age": 40,
    "emp_length": "10+ years",
}

HO_SO_XAU = {
    "cic_score": 600,
    "dti": 45.0,
    "annual_inc": 60_000_000,
    "installment": 2_200_000,
    "so_lan_tra_cuu": 8,
    "home_ownership": "OTHER",
    "loan_amnt": 50_000_000,
    "term_months": 24,
    "int_rate": 19.0,
    "person_age": 30,
    "emp_length": "1 year",
}


@pytest.fixture
def sua_config(tmp_path, monkeypatch):
    """Cho phép sửa config trong test mà không đụng file thật."""
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
    # Trỏ về file thật TRƯỚC khi nạp lại: monkeypatch chỉ hoàn tác sau fixture này,
    # nên reload ngay lúc này sẽ giữ cache là bản tạm (có thể cố ý hỏng) cho test sau.
    monkeypatch.setattr(product_config, "_CONFIG_PATH", duong_dan_goc)
    product_config.reload()


class TestBoLuat:
    def test_bo_luat_mac_dinh_dung_thu_tu(self):
        assert tuple(l.ma for l in lay_bo_luat()) == MA_LUAT_MAC_DINH

    def test_tong_diem_toi_da_bang_100(self):
        assert sum(l.diem_toi_da for l in lay_bo_luat()) == 100

    def test_ma_luat_khong_trung_nhau(self):
        ma = [l.ma for l in lay_bo_luat()]
        assert len(ma) == len(set(ma))

    def test_moi_luat_deu_co_diem_khi_thieu_trung_tinh(self):
        """Thiếu dữ liệu không được cho điểm sàn cũng không được thưởng."""
        for luat in lay_bo_luat():
            assert 0 < luat.diem_khi_thieu < luat.diem_toi_da, luat.ma

    def test_luat_mac_dinh_trong_so_bang_nhau(self):
        """Trọng số 1.0 cho cả 5 luật để kết quả không đổi so với bộ luật đã kiểm chứng."""
        assert all(l.trong_so == 1.0 for l in lay_bo_luat())

    def test_luat_khong_con_nhom_5c(self):
        assert not hasattr(_luat("CHARACTER_CIC_HISTORY"), "nhom_5c")


class TestCharacterCicHistory:
    LUAT = "CHARACTER_CIC_HISTORY"

    @pytest.mark.parametrize(
        "cic_score,diem_mong_doi",
        [
            (800, 20),
            (740, 20),
            (739, 15),
            (700, 15),
            (699, 10),
            (670, 10),
            (669, 5),
            (300, 5),
        ],
    )
    def test_cac_bac_diem(self, cic_score, diem_mong_doi):
        diem, gia_tri, thieu = _luat(self.LUAT).cham({"cic_score": cic_score})
        assert diem == diem_mong_doi
        assert gia_tri == cic_score
        assert thieu is False

    def test_thieu_cic_dung_diem_trung_tinh(self):
        """CIC chết không phải lỗi người vay — không phạt xuống điểm sàn."""
        diem, gia_tri, thieu = _luat(self.LUAT).cham({})
        assert diem == 8
        assert gia_tri is None
        assert thieu is True
        assert diem > 5, "điểm khi thiếu phải cao hơn điểm sàn (5)"


class TestCapacityExistingDebt:
    LUAT = "CAPACITY_EXISTING_DEBT"

    @pytest.mark.parametrize(
        "dti,diem_mong_doi",
        [(0, 20), (10, 20), (10.1, 15), (20, 15), (25, 8), (30, 8), (30.1, 2), (99, 2)],
    )
    def test_cac_bac_diem_nghich_dao(self, dti, diem_mong_doi):
        """DTI càng cao càng xấu — kiểm tra chiều so sánh không bị lật."""
        diem, _, thieu = _luat(self.LUAT).cham({"dti": dti})
        assert diem == diem_mong_doi
        assert thieu is False

    def test_thieu_dti(self):
        diem, _, thieu = _luat(self.LUAT).cham({})
        assert diem == 8 and thieu is True


class TestCapacityInstallmentBurden:
    LUAT = "CAPACITY_INSTALLMENT_BURDEN"

    @pytest.mark.parametrize(
        "ty_le,diem_mong_doi",
        [(0.05, 20), (0.10, 20), (0.15, 15), (0.20, 15), (0.30, 8), (0.50, 2)],
    )
    def test_cac_bac_diem(self, ty_le, diem_mong_doi):
        thu_nhap_nam = 120_000_000.0
        ho_so = {"annual_inc": thu_nhap_nam, "installment": ty_le * thu_nhap_nam / 12}
        diem, _, thieu = _luat(self.LUAT).cham(ho_so)
        assert diem == diem_mong_doi
        assert thieu is False

    def test_thu_nhap_bang_khong_coi_la_thieu(self):
        _, gia_tri, thieu = _luat(self.LUAT).cham(
            {"annual_inc": 0, "installment": 5_000_000}
        )
        assert thieu is True and gia_tri is None


class TestCharacterCreditSeeking:
    LUAT = "CHARACTER_CREDIT_SEEKING"

    @pytest.mark.parametrize(
        "so_lan,diem_mong_doi", [(0, 20), (1, 13), (2, 6), (3, 6), (4, 0), (15, 0)]
    )
    def test_cac_bac_diem(self, so_lan, diem_mong_doi):
        diem, _, thieu = _luat(self.LUAT).cham({"so_lan_tra_cuu": so_lan})
        assert diem == diem_mong_doi
        assert thieu is False

    def test_thieu_du_lieu_tra_cuu(self):
        diem, _, thieu = _luat(self.LUAT).cham({})
        assert diem == 8 and thieu is True


class TestCapitalResidenceStability:
    LUAT = "CAPITAL_RESIDENCE_STABILITY"

    def test_bang_diem_nha_o_dung_thu_tu_uu_tien(self):
        assert (
            DIEM_NHA_O_MAC_DINH["OWN"]
            > DIEM_NHA_O_MAC_DINH["MORTGAGE"]
            > DIEM_NHA_O_MAC_DINH["RENT"]
            > DIEM_NHA_O_MAC_DINH["OTHER"]
        )

    @pytest.mark.parametrize("tinh_trang", ["OWN", "MORTGAGE", "RENT", "OTHER"])
    def test_tra_bang_diem(self, tinh_trang):
        diem, gia_tri, thieu = _luat(self.LUAT).cham({"home_ownership": tinh_trang})
        assert diem == DIEM_NHA_O_MAC_DINH[tinh_trang]
        assert gia_tri == tinh_trang and thieu is False

    @pytest.mark.parametrize("ho_so", [{"home_ownership": "CASTLE"}, {}])
    def test_gia_tri_khong_hop_le_coi_nhu_thieu(self, ho_so):
        """Giá trị lạ không được im lặng nhận điểm — phải đánh dấu thiếu."""
        diem, gia_tri, thieu = _luat(self.LUAT).cham(ho_so)
        assert thieu is True and gia_tri is None and diem == 8


class TestChamDiem:
    def test_ho_so_tot_diem_cao_hon_ho_so_xau(self):
        assert tinh_diem_rui_ro(HO_SO_TOT) > tinh_diem_rui_ro(HO_SO_XAU)

    def test_ho_so_hoan_hao_dat_diem_toi_da(self):
        """Thang điểm phải dùng được hết 100 — bản cũ không bao giờ vượt 80."""
        assert tinh_diem_rui_ro(HO_SO_TOT) == 100

    @pytest.mark.parametrize("ho_so", [HO_SO_TOT, HO_SO_XAU, {}, {"annual_inc": 0}])
    def test_diem_luon_trong_thang_0_100(self, ho_so):
        """finora-loan validate risk_score 0-100; ra ngoài là phá hợp đồng."""
        assert 0 <= tinh_diem_rui_ro(ho_so) <= 100

    def test_ho_so_rong_khong_crash(self):
        diem, vet = cham_diem_chi_tiet({})
        assert 0 <= diem <= 100
        assert all(m["thieu_du_lieu"] for m in vet)


class TestRuleTrace:
    def test_trace_co_du_moi_luat(self):
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        assert [m["ma"] for m in vet] == list(MA_LUAT_MAC_DINH)

    def test_tong_diem_trace_bang_risk_score(self):
        """Vết luật phải giải thích được TOÀN BỘ điểm, không thiếu không thừa."""
        diem, vet = cham_diem_chi_tiet(HO_SO_XAU)
        tong = sum(m["diem"] * m["trong_so"] for m in vet)
        tran = sum(m["toi_da"] * m["trong_so"] for m in vet)
        assert round(tong * 100 / tran) == diem

    def test_moi_muc_trace_du_truong_giai_trinh(self):
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        for muc in vet:
            assert set(muc) == {
                "ma",
                "mo_ta",
                "truong",
                "gia_tri",
                "diem",
                "toi_da",
                "trong_so",
                "thieu_du_lieu",
            }
            assert 0 <= muc["diem"] <= muc["toi_da"]

    def test_trace_ghi_dung_truong_da_doc(self):
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        theo_ma = {m["ma"]: m for m in vet}
        assert theo_ma["CHARACTER_CIC_HISTORY"]["truong"] == "cic_score"
        assert theo_ma["CAPITAL_RESIDENCE_STABILITY"]["truong"] == "home_ownership"

    def test_danh_dau_dung_luat_nao_thieu_du_lieu(self):
        ho_so = {"cic_score": 750, "dti": 5.0, "home_ownership": "OWN"}
        _, vet = cham_diem_chi_tiet(ho_so)
        thieu = {m["ma"] for m in vet if m["thieu_du_lieu"]}
        assert thieu == {"CAPACITY_INSTALLMENT_BURDEN", "CHARACTER_CREDIT_SEEKING"}

    def test_dem_luat_co_du_lieu(self):
        _, vet_day = cham_diem_chi_tiet(HO_SO_TOT)
        _, vet_rong = cham_diem_chi_tiet({})
        assert dem_luat_co_du_lieu(vet_day) == len(MA_LUAT_MAC_DINH)
        assert dem_luat_co_du_lieu(vet_rong) == 0


# Luật mẫu do admin tự tạo, dùng cho các test thêm luật qua config.
LUAT_TUOI = {
    "ma": "AGE_BRACKET",
    "mo_ta": "Tuổi người vay — độ tuổi lao động ổn định",
    "truong": "person_age",
    "nghich_dao": False,
    "trong_so": 1.0,
    "bat": True,
    "diem_khi_thieu": 10,
    "bac": [[25, 20], [0, 5]],
}


class TestLuatTuCauHinh:
    """Admin thêm luật mới hoàn toàn bằng config — engine không cần biết trước."""

    def test_them_luat_moi_duoc_cham_va_vao_trace(self, sua_config):
        sua_config(lambda c: c["rules"].append(LUAT_TUOI))
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        muc = next(m for m in vet if m["ma"] == "AGE_BRACKET")
        assert (
            muc["gia_tri"] == 40 and muc["diem"] == 20 and muc["truong"] == "person_age"
        )

    def test_luat_phan_loai_tu_cau_hinh(self, sua_config):
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
        diem, gia_tri, thieu = _luat("PURPOSE_RISK").cham({"purpose": "education"})
        assert (diem, gia_tri, thieu) == (18, "education", False)

    def test_trong_so_lam_lech_diem_theo_dung_ty_le(self, sua_config):
        """Luật nặng gấp đôi thì mất điểm ở luật đó kéo tổng xuống gấp đôi."""
        ho_so = {**HO_SO_TOT, "cic_score": 600}  # chỉ luật CIC hụt điểm (5/20)

        sua_config(lambda c: None)
        diem_1 = tinh_diem_rui_ro(ho_so)
        sua_config(
            lambda c: _luat_config(c, "CHARACTER_CIC_HISTORY").update(trong_so=2.0)
        )
        diem_2 = tinh_diem_rui_ro(ho_so)

        # trọng số 1: (80+5)/100 = 85; trọng số 2: (80+10)/120 = 75
        assert diem_1 == 85 and diem_2 == 75

    def test_trong_so_ghi_vao_trace(self, sua_config):
        sua_config(
            lambda c: _luat_config(c, "CHARACTER_CIC_HISTORY").update(trong_so=2.5)
        )
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        assert (
            next(m for m in vet if m["ma"] == "CHARACTER_CIC_HISTORY")["trong_so"]
            == 2.5
        )

    def test_xoa_luat_mac_dinh_duoc(self, sua_config):
        sua_config(lambda c: c["rules"].pop(0))
        assert "CHARACTER_CIC_HISTORY" not in {l.ma for l in lay_bo_luat()}
        assert tinh_diem_rui_ro(HO_SO_TOT) == 100

    def test_truong_khong_co_trong_danh_muc_bao_loi_ro(self, sua_config):
        """Config hỏng phải nổ ngay với thông báo chỉ đúng luật, không im lặng cho 0 điểm."""
        sua_config(lambda c: c["rules"].append({**LUAT_TUOI, "truong": "int_rate"}))
        with pytest.raises(ValueError, match="AGE_BRACKET.*int_rate"):
            lay_bo_luat()


class TestChotChanCung:
    def test_ho_so_sach_khong_vi_pham(self):
        assert kiem_tra_chot_chan_cung(HO_SO_TOT) == []

    def test_lai_suat_vuot_tran_20_phan_tram(self):
        """Điều 468 Bộ luật Dân sự 2015."""
        assert "INTEREST_RATE_EXCEEDS_LEGAL_LIMIT" in kiem_tra_chot_chan_cung(
            {**HO_SO_TOT, "int_rate": 25.0}
        )

    def test_lai_suat_dung_20_phan_tram_van_hop_le(self):
        """Ranh giới: đúng trần là hợp lệ, chỉ vượt mới bị chặn."""
        assert kiem_tra_chot_chan_cung({**HO_SO_TOT, "int_rate": 20.0}) == []

    def test_lai_suat_am_bi_chan(self):
        assert "INVALID_INTEREST_RATE" in kiem_tra_chot_chan_cung(
            {**HO_SO_TOT, "int_rate": 0}
        )

    def test_ky_han_vuot_24_thang(self):
        """Nghị định 94/2025/NĐ-CP."""
        assert "TERM_EXCEEDS_LEGAL_LIMIT" in kiem_tra_chot_chan_cung(
            {**HO_SO_TOT, "term_months": 36}
        )

    @pytest.mark.parametrize("nhom_no", [3, 4, 5])
    def test_no_xau_cic_bi_chan(self, nhom_no):
        """Nợ nhóm 3+ là nợ xấu theo Thông tư 11/2021/TT-NHNN."""
        assert "CIC_BAD_DEBT_GROUP" in kiem_tra_chot_chan_cung(
            {**HO_SO_TOT, "nhom_no_cao_nhat": nhom_no}
        )

    @pytest.mark.parametrize("nhom_no", [1, 2])
    def test_no_nhom_1_2_khong_bi_chan(self, nhom_no):
        assert kiem_tra_chot_chan_cung({**HO_SO_TOT, "nhom_no_cao_nhat": nhom_no}) == []

    def test_tong_du_no_vuot_400_trieu_bi_chan(self):
        """Trần tổng 400 triệu toàn hệ thống — Quyết định 2866/QĐ-NHNN."""
        ho_so = {**HO_SO_TOT, "tong_du_no": 380_000_000, "loan_amnt": 50_000_000}
        assert "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT" in kiem_tra_chot_chan_cung(ho_so)

    def test_tong_du_no_dung_400_trieu_van_hop_le(self):
        """Ranh giới: đúng trần là hợp lệ, chỉ vượt mới bị chặn."""
        ho_so = {**HO_SO_TOT, "tong_du_no": 350_000_000, "loan_amnt": 50_000_000}
        assert "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT" not in kiem_tra_chot_chan_cung(ho_so)

    def test_cong_ca_khoan_dang_xin_vay(self):
        """Dư nợ hiện có dưới trần nhưng cộng khoản mới thì vượt — phải chặn.

        Nếu chỉ kiểm dư nợ hiện có, khoản vay đẩy người vay vượt trần sẽ luôn lọt.
        """
        ho_so = {**HO_SO_TOT, "tong_du_no": 390_000_000, "loan_amnt": 20_000_000}
        assert "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT" in kiem_tra_chot_chan_cung(ho_so)

    def test_thieu_du_no_cic_khong_coi_la_vi_pham(self):
        """CIC không tra được là sự cố hạ tầng, không phải bằng chứng vượt trần."""
        ho_so = {**HO_SO_TOT, "tong_du_no": None, "loan_amnt": 50_000_000}
        assert "TOTAL_DEBT_EXCEEDS_LEGAL_LIMIT" not in kiem_tra_chot_chan_cung(ho_so)

    def test_tra_ve_TAT_CA_vi_pham_khong_dung_o_loi_dau(self):
        """Người vay phải thấy hết lỗi để sửa một lần, không quay lại nhiều vòng."""
        ho_so = {
            **HO_SO_TOT,
            "int_rate": 30.0,
            "term_months": 48,
            "nhom_no_cao_nhat": 5,
        }
        vi_pham = kiem_tra_chot_chan_cung(ho_so)
        assert {
            "INTEREST_RATE_EXCEEDS_LEGAL_LIMIT",
            "TERM_EXCEEDS_LEGAL_LIMIT",
            "CIC_BAD_DEBT_GROUP",
        } <= set(vi_pham)

    def test_ho_so_rong_khong_vi_pham_gia(self):
        """Thiếu dữ liệu không phải vi phạm pháp lý — xử lý ở nhánh quyết định."""
        assert kiem_tra_chot_chan_cung({}) == []


class TestNguongLuatCoDuLieu:
    """Số luật tối thiểu phải có dữ liệu thật tính theo TỶ LỆ số luật đang chấm.

    Hằng số 3 của bản cũ chỉ đúng với 5 luật: admin thêm lên 10 luật thì 3/10 có
    dữ liệu vẫn được máy quyết — quá lỏng. Tỷ lệ 60% cho đúng 3 với 5 luật (giữ
    hành vi đã kiểm chứng) và tự nâng lên khi bộ luật lớn hơn.
    """

    def test_ty_le_giu_dung_hanh_vi_cu_voi_5_luat(self):
        assert TY_LE_LUAT_TOI_THIEU_CO_DU_LIEU == 0.6
        assert so_luat_toi_thieu_co_du_lieu(5) == 3

    @pytest.mark.parametrize(
        "so_luat,mong_doi", [(1, 1), (2, 2), (4, 3), (8, 5), (10, 6)]
    )
    def test_lam_tron_len(self, so_luat, mong_doi):
        assert so_luat_toi_thieu_co_du_lieu(so_luat) == mong_doi

    def test_khong_luat_nao_thi_nguong_0(self):
        assert so_luat_toi_thieu_co_du_lieu(0) == 0


class TestQuyetDinh:
    def test_vi_pham_luon_bi_tu_choi_du_diem_cao(self):
        assert quyet_dinh(99.0, ["CIC_BAD_DEBT_GROUP"], 5, 5) == "REJECTED"

    def test_thieu_du_lieu_thi_cho_tham_dinh_khong_tu_choi(self):
        """Không tra được thông tin là sự cố nền tảng, không phải lỗi người vay."""
        assert quyet_dinh(90.0, [], 2, 5) == "PENDING_REVIEW"

    def test_nguong_thieu_du_lieu_theo_so_luat_da_cham(self):
        """3/10 luật có dữ liệu là chưa đủ, dù 3/5 thì đủ."""
        assert quyet_dinh(90.0, [], 3, 5) == "APPROVED"
        assert quyet_dinh(90.0, [], 3, 10) == "PENDING_REVIEW"

    def test_diem_cao_du_du_lieu_thi_duyet(self):
        assert quyet_dinh(90.0, [], 5, 5) == "APPROVED"

    def test_diem_thap_thi_tu_choi(self):
        assert quyet_dinh(50.0, [], 5, 5) == "REJECTED"

    def test_vung_xam_cho_tham_dinh(self):
        assert quyet_dinh(72.0, [], 5, 5) == "PENDING_REVIEW"

    def test_khong_truyen_so_luat_van_chay_duoc(self):
        """Tương thích ngược với chỗ gọi cũ."""
        assert quyet_dinh(90.0) == "APPROVED"

    def test_thieu_tong_so_luat_thi_lay_theo_bo_luat_dang_bat(self):
        """Chỗ gọi cũ chỉ truyền số luật có dữ liệu — mẫu số lấy từ config hiện tại."""
        assert quyet_dinh(90.0, [], 5) == "APPROVED"
        assert quyet_dinh(90.0, [], 2) == "PENDING_REVIEW"


class TestXepHang:
    @pytest.mark.parametrize("diem", [0, 37, 50, 69, 84, 100])
    def test_hang_luon_thuoc_bo_hop_le(self, diem):
        """Hạng phải nằm trong A-E.

        Hạng E được thêm 2026-09-04 cùng model v17: nhóm điểm dưới 37 có tỷ lệ vỡ
        nợ 40,6% (lift 2,47x) nên hạn mức phải là 0, không thể gộp vào D như trước.
        LƯU Ý: `finora-loan` đang validate `credit_grade in (A,B,C,D)` — phải mở
        rộng bên đó trước khi bật hạng E trên môi trường có Loan gọi sang.
        """
        assert xep_hang(diem).hang in {"A", "B", "C", "D", "E"}

    def test_han_muc_khong_vuot_tran_phap_ly(self):
        """Trần 100 triệu/nền tảng theo Nghị định 94/2025."""
        for diem in range(0, 101, 5):
            assert xep_hang(diem).han_muc <= 100_000_000

    def test_diem_cao_hon_khong_bao_gio_cho_hang_thap_hon(self):
        thu_tu = {"E": 0, "D": 1, "C": 2, "B": 3, "A": 4}
        hang = [thu_tu[xep_hang(d).hang] for d in range(101)]
        assert hang == sorted(hang), "xếp hạng phải đơn điệu theo điểm"


class TestDiemTongHop:
    def test_pd_thap_cho_diem_cao_hon(self):
        assert tinh_diem_tong_hop(0.02, 70) > tinh_diem_tong_hop(0.40, 70)

    def test_risk_score_cao_cho_diem_cao_hon(self):
        assert tinh_diem_tong_hop(0.10, 90) > tinh_diem_tong_hop(0.10, 40)

    @pytest.mark.parametrize("pd_p,risk", [(0.0, 0), (1.0, 100), (0.5, 50)])
    def test_diem_tong_hop_luon_trong_0_100(self, pd_p, risk):
        assert 0 <= tinh_diem_tong_hop(pd_p, risk) <= 100


class TestBatTatLuat:
    """Tắt luật phải giữ nguyên thang điểm 100, nếu không mọi hồ sơ tụt hạng oan."""

    def test_tat_mot_luat_van_giu_thang_100(self, sua_config):
        truoc = tinh_diem_rui_ro(HO_SO_TOT)
        sua_config(lambda c: _luat_config(c, "CHARACTER_CIC_HISTORY").update(bat=False))
        sau = tinh_diem_rui_ro(HO_SO_TOT)
        assert truoc == sau == 100, (
            "hồ sơ hoàn hảo phải vẫn đạt 100 sau khi tắt một luật"
        )

    def test_luat_tat_khong_xuat_hien_trong_trace(self, sua_config):
        sua_config(lambda c: _luat_config(c, "CHARACTER_CIC_HISTORY").update(bat=False))
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        assert len(vet) == len(MA_LUAT_MAC_DINH) - 1
        assert "CHARACTER_CIC_HISTORY" not in {m["ma"] for m in vet}

    def test_tat_het_luat_tra_ve_0_khong_crash(self, sua_config):
        """Không được chia cho 0, và không được cho điểm khống."""
        sua_config(lambda c: [r.update(bat=False) for r in c["rules"]])
        diem, vet = cham_diem_chi_tiet(HO_SO_TOT)
        assert diem == 0 and vet == []

    def test_sua_nguong_co_hieu_luc_ngay(self, sua_config):
        """Không cần khởi động lại service — đây là điểm cốt lõi của rule engine."""
        assert _luat("CHARACTER_CIC_HISTORY").cham({"cic_score": 750})[0] == 20
        sua_config(
            lambda c: _luat_config(c, "CHARACTER_CIC_HISTORY").update(
                bac=[[800, 20], [700, 15], [670, 10], [0, 5]]
            )
        )
        assert _luat("CHARACTER_CIC_HISTORY").cham({"cic_score": 750})[0] == 15

    @pytest.mark.parametrize("so_luat_tat", [1, 2, 3, 4])
    def test_diem_luon_trong_thang_du_tat_bao_nhieu_luat(self, sua_config, so_luat_tat):
        """finora-loan validate risk_score 0-100 bất kể admin cấu hình thế nào."""
        sua_config(lambda c: [r.update(bat=False) for r in c["rules"][:so_luat_tat]])
        for ho_so in (HO_SO_TOT, HO_SO_XAU, {}):
            assert 0 <= tinh_diem_rui_ro(ho_so) <= 100
