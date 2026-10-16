"""MRZ TD1 bố cục Việt Nam: check digit, parse, sửa ký tự nhầm, Tesseract (nếu có)."""

from datetime import date

import pytest

from app.ml.ekyc import mrz, tesseract_ocr

from .synthetic import MRZ_REAL_SAMPLE, MRZ_SYNTHETIC, back_card


class TestCheckDigit:
    @pytest.mark.parametrize(
        ("field", "digit"),
        [
            ("069005251", "2"),  # số giấy tờ của mẫu thật
            ("690428", "1"),  # ngày sinh của mẫu thật
            ("290428", "3"),  # hạn thẻ của mẫu thật
            ("095012345", "5"),
            ("950315", "7"),
            ("<<<<<<", "0"),  # '<' tính là 0
            ("AB<", str((10 * 7 + 11 * 3) % 10)),  # A=10, B=11
        ],
    )
    def test_trong_so_7_3_1(self, field, digit):
        assert mrz.check_digit(field) == digit


class TestParseTd1:
    def test_mau_that_hop_le_va_doc_dung_truong(self):
        result = mrz.parse_td1(*MRZ_REAL_SAMPLE, None)
        assert result.valid
        assert result.id_number == "046069005251"
        # Số thứ 4 là 0 → thế kỷ 20, nam.
        assert result.birth_date == date(1969, 4, 28)
        assert result.gender == "Nam"
        assert result.expiry_date == date(2029, 4, 28)
        assert result.id12_ok

    def test_mau_tong_hop_va_ten_dong_3(self):
        result = mrz.parse_td1(*MRZ_SYNTHETIC)
        assert result.valid
        assert result.id_number == "001095012345"
        assert result.birth_date == date(1995, 3, 15)
        assert result.expiry_date == date(2035, 3, 15)
        assert result.full_name == "NGUYEN VAN A"
        assert (result.surname, result.given_names) == ("NGUYEN", "VAN A")

    def test_sai_check_digit_han_the_thi_khong_hop_le(self):
        line2 = MRZ_SYNTHETIC[1][:14] + "4" + MRZ_SYNTHETIC[1][15:]
        result = mrz.parse_td1(MRZ_SYNTHETIC[0], line2, None)
        assert not result.valid
        assert not result.expiry_ok
        assert not result.composite_ok  # composite phủ cả hạn thẻ

    def test_sai_mot_so_trong_id12_bi_composite_bat(self):
        # Đổi số thứ 2 của ID 12 số (ngoài vùng doc number) → chỉ composite và id12 lộ ra.
        line1 = MRZ_SYNTHETIC[0][:16] + "9" + MRZ_SYNTHETIC[0][17:]
        result = mrz.parse_td1(line1, MRZ_SYNTHETIC[1], None)
        assert not result.valid
        assert not result.composite_ok

    def test_so_giay_to_khac_9_so_cuoi_id_thi_sai_bo_cuc(self):
        line1 = "IDVNM095012346" + mrz.check_digit("095012346") + MRZ_SYNTHETIC[0][15:]
        result = mrz.parse_td1(line1, MRZ_SYNTHETIC[1], None)
        assert result.doc_number_ok
        assert not result.layout_ok
        assert not result.valid

    def test_the_khong_thoi_han(self):
        line2 = "9503157M<<<<<<0VNM<<<<<<<<<<<"
        line2 += mrz.check_digit(
            MRZ_SYNTHETIC[0][5:30] + line2[0:7] + line2[8:15] + line2[18:29]
        )
        result = mrz.parse_td1(MRZ_SYNTHETIC[0], line2, None)
        assert result.no_expiry
        assert result.expiry_date is None
        assert result.valid

    def test_dong_ngan_qua_tra_none(self):
        assert mrz.parse_td1("IDVNM0950", "9503157M", None) is None


class TestRepair:
    def test_chu_nham_trong_truong_so_duoc_doi_ve_so(self):
        noisy1 = "1DVNMO95O12345SOO1O95O12345<<6"  # I→1, 0→O, 5→S
        noisy2 = "95O3I57M35O3I55VNM<<<<<<<<<<<Z"  # Z→2 ở composite
        result = mrz.parse_td1(noisy1, noisy2, "NGUYEN<<VAN<A")
        assert result.valid
        assert result.lines[0] == MRZ_SYNTHETIC[0]
        assert result.lines[1] == MRZ_SYNTHETIC[1]

    def test_ky_tu_dem_doc_thua_thieu_van_dung_lai_duoc(self):
        result = mrz.parse_td1(
            "IDVNM0950123455001095012345<6", "9503157M3503155VNM<<<<<<<<<2", None
        )
        assert result.valid
        assert result.lines[0] == MRZ_SYNTHETIC[0]

    def test_dau_nhay_va_khoang_trang_cua_ocr(self):
        result = mrz.parse_td1(
            " IDVNM 095012345 5 001095012345 «6 ",
            "9503157M3503155VNM<<<<<<<<<<<2",
            None,
        )
        assert result.valid

    def test_khong_doan_so_bang_so_de_ep_qua_check_digit(self):
        # '8' bị đọc thành '6' là nhầm số-số: không sửa (tránh "sửa nhầm vẫn qua").
        line2 = "9503157M3503155VNM<<<<<<<<<<<2".replace("950315", "950316")
        result = mrz.parse_td1(MRZ_SYNTHETIC[0], line2, None)
        assert not result.valid


class TestCandidates:
    def test_tim_bo_ba_dong_trong_van_ban_ocr(self):
        text = "BO CONG AN\n" + "\n".join(MRZ_SYNTHETIC) + "\n"
        triples = mrz.candidate_triples(text.splitlines())
        assert len(triples) == 1
        read = mrz.best_of(triples, mrz.ENGINE_TESSERACT)
        assert read.found and read.valid and read.engine == "TESSERACT"

    def test_thay_mrz_nhung_sai_thi_found_va_khong_valid(self):
        bad = [MRZ_SYNTHETIC[0], MRZ_SYNTHETIC[1][:-1] + "9", MRZ_SYNTHETIC[2]]
        read = mrz.best_of(mrz.candidate_triples(bad), mrz.ENGINE_GEMINI)
        assert read.found and not read.valid
        checks = mrz.checks_dict(read)
        assert checks["found"] and not checks["valid"]
        assert checks["doc_number_ok"] and not checks["composite_ok"]

    def test_khong_co_dong_giong_mrz(self):
        read = mrz.best_of(mrz.candidate_triples(["XIN CHAO", "1234"]), "TESSERACT")
        assert not read.found
        assert mrz.checks_dict(read) == mrz.blank_checks()


def _tesseract_or_skip():
    if not tesseract_ocr.available():
        pytest.skip("Máy không có Tesseract (cài tesseract-ocr để chạy test tích hợp)")


def test_tesseract_doc_mrz_tren_the_tong_hop():
    _tesseract_or_skip()
    card = back_card(MRZ_SYNTHETIC)
    read = mrz.read_with_tesseract(card, timeout_s=20)
    assert read is not None and read.found
    if read.valid:
        assert read.result.id_number == "001095012345"
