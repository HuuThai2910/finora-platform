"""Test rule engine: từng luật, chấm điểm tổng, vết luật, chốt chặn và quyết định.

Đây là tầng quyết định duyệt hay từ chối tiền thật, nên test tập trung vào những
sai lầm KHÔNG gây crash: điểm ra ngoài thang, hồ sơ rỗng lọt qua, vi phạm pháp lý
bị bỏ sót, vết luật không khớp điểm.
"""
import pytest

from app.services.credit.rule_engine import (
    MA_LUAT_HOP_LE,
    SO_LUAT_TOI_THIEU_CO_DU_LIEU,
    LuatChamDiem,
    _lay_ty_le_tra_no_thang,
    _so_hoac_none,
    _tinh_tien_tra_thang,
    cham_diem_chi_tiet,
    dem_luat_co_du_lieu,
    kiem_tra_chot_chan_cung,
    lay_bo_luat,
    quyet_dinh,
    tinh_diem_rui_ro,
    tinh_diem_tong_hop,
    xep_hang,
)

# Bảng điểm nhà ở mặc định trong config — test khẳng định giá trị này để việc
# sửa config ngoài ý muốn bị phát hiện, thay vì đọc lại chính config đang test.
DIEM_NHA_O_MAC_DINH = {"OWN": 20, "MORTGAGE": 16, "RENT": 8, "OTHER": 4}


def _luat(ma: str) -> LuatChamDiem:
    return next(l for l in lay_bo_luat() if l.ma == ma)

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


class TestBoLuat:
    def test_tong_diem_toi_da_bang_100(self):
        assert sum(l.diem_toi_da for l in lay_bo_luat()) == 100

    def test_ma_luat_khong_trung_nhau(self):
        ma = [l.ma for l in lay_bo_luat()]
        assert len(ma) == len(set(ma)) == len(MA_LUAT_HOP_LE)

    def test_moi_luat_deu_co_diem_khi_thieu_trung_tinh(self):
        """Thiếu dữ liệu không được cho điểm sàn cũng không được thưởng."""
        for luat in lay_bo_luat():
            assert 0 < luat.diem_khi_thieu < luat.diem_toi_da, luat.ma


class TestCharacterCicHistory:
    LUAT = "CHARACTER_CIC_HISTORY"

    @pytest.mark.parametrize(
        "cic_score,diem_mong_doi",
        [(800, 20), (740, 20), (739, 15), (700, 15), (699, 10), (670, 10), (669, 5), (300, 5)],
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
        "ty_le,diem_mong_doi", [(0.05, 20), (0.10, 20), (0.15, 15), (0.20, 15), (0.30, 8), (0.50, 2)]
    )
    def test_cac_bac_diem(self, ty_le, diem_mong_doi):
        thu_nhap_nam = 120_000_000.0
        ho_so = {"annual_inc": thu_nhap_nam, "installment": ty_le * thu_nhap_nam / 12}
        diem, _, thieu = _luat(self.LUAT).cham(ho_so)
        assert diem == diem_mong_doi
        assert thieu is False

    def test_tu_tinh_khi_thieu_installment(self):
        """Không có installment thì tính từ lãi suất và kỳ hạn."""
        ho_so = {
            "annual_inc": 120_000_000,
            "loan_amnt": 12_000_000,
            "term_months": 12,
            "int_rate": 12.0,
        }
        _, gia_tri, thieu = _luat(self.LUAT).cham(ho_so)
        assert thieu is False
        assert gia_tri is not None and gia_tri > 0

    def test_thu_nhap_bang_khong_coi_la_thieu(self):
        """Chia cho 0 phải trả None, không được crash hay ra vô cực."""
        _, gia_tri, thieu = _luat(self.LUAT).cham({"annual_inc": 0, "installment": 5_000_000})
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
        assert DIEM_NHA_O_MAC_DINH["OWN"] > DIEM_NHA_O_MAC_DINH["MORTGAGE"] > DIEM_NHA_O_MAC_DINH["RENT"] > DIEM_NHA_O_MAC_DINH["OTHER"]

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


class TestHamTienIch:
    @pytest.mark.parametrize("dau_vao", [None, "abc", float("nan")])
    def test_so_hoac_none_loai_gia_tri_khong_dung(self, dau_vao):
        assert _so_hoac_none(dau_vao) is None

    @pytest.mark.parametrize("dau_vao,mong_doi", [(5, 5.0), ("12.5", 12.5), (0, 0.0)])
    def test_so_hoac_none_giu_gia_tri_hop_le(self, dau_vao, mong_doi):
        assert _so_hoac_none(dau_vao) == mong_doi

    def test_tinh_tien_tra_thang_lai_suat_khong(self):
        """Lãi 0% thì chia đều gốc, không được chia cho 0."""
        tien = _tinh_tien_tra_thang({"loan_amnt": 12_000_000, "term_months": 12, "int_rate": 0})
        assert tien == pytest.approx(1_000_000)

    def test_tien_tra_thang_tang_dan_theo_lai_suat(self):
        """Lãi suất cao hơn thì trả nhiều hơn — ở MỌI khoảng, kể cả quanh mốc 1,0.

        Bản trước đoán đơn vị bằng `lai <= 1.0`, nên `int_rate=1.0` bị hiểu là
        100%/năm còn `2.0` là 2%/năm: tiền trả GIẢM khi lãi suất tăng. Test cũ
        (`12.0` và `0.12` cho cùng kết quả) khẳng định chính cách đoán đó, nên nó
        được thay bằng tính đơn điệu — thứ luôn đúng với một hàm niên kim.
        """
        chung = {"loan_amnt": 12_000_000, "term_months": 12}
        tien = [
            _tinh_tien_tra_thang({**chung, "int_rate": ls})
            for ls in (0.5, 1.0, 2.0, 5.0, 12.0, 18.0)
        ]
        assert tien == sorted(tien), f"tiền trả không tăng đơn điệu: {tien}"

    def test_int_rate_luon_doc_la_phan_tram_nam(self):
        """`int_rate` là %/năm đúng như schema khai, không đoán theo độ lớn."""
        chung = {"loan_amnt": 12_000_000, "term_months": 12}
        # 12%/năm = 1%/tháng: công thức niên kim cho 1.066.185 đ.
        assert _tinh_tien_tra_thang({**chung, "int_rate": 12.0}) == pytest.approx(
            1_066_185, rel=1e-4
        )
        # 0,12%/năm là khoản vay gần như không lãi, KHÔNG phải 12%/năm.
        assert _tinh_tien_tra_thang({**chung, "int_rate": 0.12}) == pytest.approx(
            1_000_650, rel=1e-4
        )

    def test_thieu_du_lieu_thi_khong_tinh_duoc(self):
        assert _tinh_tien_tra_thang({"loan_amnt": 12_000_000}) is None
        assert _lay_ty_le_tra_no_thang({"installment": 1_000_000}) is None


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
        assert len(vet) == len(MA_LUAT_HOP_LE)
        assert [m["ma"] for m in vet] == list(MA_LUAT_HOP_LE)

    def test_tong_diem_trace_bang_risk_score(self):
        """Vết luật phải giải thích được TOÀN BỘ điểm, không thiếu không thừa."""
        diem, vet = cham_diem_chi_tiet(HO_SO_XAU)
        assert sum(m["diem"] for m in vet) == diem

    def test_moi_muc_trace_du_truong_giai_trinh(self):
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        for muc in vet:
            assert set(muc) == {
                "ma", "nhom_5c", "mo_ta", "gia_tri", "diem", "toi_da", "thieu_du_lieu"
            }
            assert 0 <= muc["diem"] <= muc["toi_da"]

    def test_danh_dau_dung_luat_nao_thieu_du_lieu(self):
        ho_so = {"cic_score": 750, "dti": 5.0, "home_ownership": "OWN"}
        _, vet = cham_diem_chi_tiet(ho_so)
        thieu = {m["ma"] for m in vet if m["thieu_du_lieu"]}
        assert thieu == {"CAPACITY_INSTALLMENT_BURDEN", "CHARACTER_CREDIT_SEEKING"}

    def test_dem_luat_co_du_lieu(self):
        _, vet_day = cham_diem_chi_tiet(HO_SO_TOT)
        _, vet_rong = cham_diem_chi_tiet({})
        assert dem_luat_co_du_lieu(vet_day) == len(MA_LUAT_HOP_LE)
        assert dem_luat_co_du_lieu(vet_rong) == 0


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

    def test_ty_le_tra_no_vuot_50_phan_tram(self):
        ho_so = {**HO_SO_TOT, "annual_inc": 60_000_000, "installment": 4_000_000}
        assert "DEBT_SERVICE_RATIO_TOO_HIGH" in kiem_tra_chot_chan_cung(ho_so)

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

    def test_tuoi_va_tham_nien_mau_thuan(self):
        """25 tuổi mà 20 năm kinh nghiệm nghĩa là đi làm từ năm 5 tuổi."""
        assert "AGE_AND_EXPERIENCE_INCONSISTENCY" in kiem_tra_chot_chan_cung(
            {**HO_SO_TOT, "person_age": 25, "emp_length_years": 20}
        )

    def test_tra_ve_TAT_CA_vi_pham_khong_dung_o_loi_dau(self):
        """Người vay phải thấy hết lỗi để sửa một lần, không quay lại nhiều vòng."""
        ho_so = {**HO_SO_TOT, "int_rate": 30.0, "term_months": 48, "nhom_no_cao_nhat": 5}
        vi_pham = kiem_tra_chot_chan_cung(ho_so)
        assert {
            "INTEREST_RATE_EXCEEDS_LEGAL_LIMIT",
            "TERM_EXCEEDS_LEGAL_LIMIT",
            "CIC_BAD_DEBT_GROUP",
        } <= set(vi_pham)

    def test_ho_so_rong_khong_vi_pham_gia(self):
        """Thiếu dữ liệu không phải vi phạm pháp lý — xử lý ở nhánh quyết định."""
        assert kiem_tra_chot_chan_cung({}) == []


class TestQuyetDinh:
    def test_vi_pham_luon_bi_tu_choi_du_diem_cao(self):
        assert quyet_dinh(99.0, ["CIC_BAD_DEBT_GROUP"], 5) == "REJECTED"

    def test_thieu_du_lieu_thi_cho_tham_dinh_khong_tu_choi(self):
        """Không tra được thông tin là sự cố nền tảng, không phải lỗi người vay."""
        assert quyet_dinh(90.0, [], SO_LUAT_TOI_THIEU_CO_DU_LIEU - 1) == "PENDING_REVIEW"

    def test_diem_cao_du_du_lieu_thi_duyet(self):
        assert quyet_dinh(90.0, [], 5) == "APPROVED"

    def test_diem_thap_thi_tu_choi(self):
        assert quyet_dinh(50.0, [], 5) == "REJECTED"

    def test_vung_xam_cho_tham_dinh(self):
        assert quyet_dinh(72.0, [], 5) == "PENDING_REVIEW"

    def test_khong_truyen_so_luat_van_chay_duoc(self):
        """Tương thích ngược với chỗ gọi cũ."""
        assert quyet_dinh(90.0) == "APPROVED"


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

    @pytest.fixture
    def sua_config(self, tmp_path, monkeypatch):
        """Cho phép sửa config trong test mà không đụng file thật."""
        import json

        from app.services.credit import product_config

        goc = json.loads(product_config._CONFIG_PATH.read_text(encoding="utf-8"))
        file_tam = tmp_path / "product_config.json"
        monkeypatch.setattr(product_config, "_CONFIG_PATH", file_tam)

        def ghi(sua):
            cau_hinh = json.loads(json.dumps(goc))
            sua(cau_hinh)
            file_tam.write_text(json.dumps(cau_hinh, ensure_ascii=False), encoding="utf-8")
            product_config.reload()

        ghi(lambda c: None)
        yield ghi
        product_config.reload()

    def test_tat_mot_luat_van_giu_thang_100(self, sua_config):
        truoc = tinh_diem_rui_ro(HO_SO_TOT)
        sua_config(lambda c: c["rules"]["CHARACTER_CIC_HISTORY"].update(bat=False))
        sau = tinh_diem_rui_ro(HO_SO_TOT)
        assert truoc == sau == 100, "hồ sơ hoàn hảo phải vẫn đạt 100 sau khi tắt một luật"

    def test_luat_tat_khong_xuat_hien_trong_trace(self, sua_config):
        sua_config(lambda c: c["rules"]["CHARACTER_CIC_HISTORY"].update(bat=False))
        _, vet = cham_diem_chi_tiet(HO_SO_TOT)
        assert len(vet) == len(MA_LUAT_HOP_LE) - 1
        assert "CHARACTER_CIC_HISTORY" not in {m["ma"] for m in vet}

    def test_tat_het_luat_tra_ve_0_khong_crash(self, sua_config):
        """Không được chia cho 0, và không được cho điểm khống."""
        sua_config(
            lambda c: [r.update(bat=False) for r in c["rules"].values()]
        )
        diem, vet = cham_diem_chi_tiet(HO_SO_TOT)
        assert diem == 0 and vet == []

    def test_sua_nguong_co_hieu_luc_ngay(self, sua_config):
        """Không cần khởi động lại service — đây là điểm cốt lõi của rule engine."""
        assert _luat("CHARACTER_CIC_HISTORY").cham({"cic_score": 750})[0] == 20
        sua_config(
            lambda c: c["rules"]["CHARACTER_CIC_HISTORY"].update(
                bac=[[800, 20], [700, 15], [670, 10], [0, 5]]
            )
        )
        assert _luat("CHARACTER_CIC_HISTORY").cham({"cic_score": 750})[0] == 15

    @pytest.mark.parametrize("so_luat_tat", [1, 2, 3, 4])
    def test_diem_luon_trong_thang_du_tat_bao_nhieu_luat(self, sua_config, so_luat_tat):
        """finora-loan validate risk_score 0-100 bất kể admin cấu hình thế nào."""
        ma_tat = list(MA_LUAT_HOP_LE)[:so_luat_tat]
        sua_config(lambda c: [c["rules"][m].update(bat=False) for m in ma_tat])
        for ho_so in (HO_SO_TOT, HO_SO_XAU, {}):
            assert 0 <= tinh_diem_rui_ro(ho_so) <= 100
