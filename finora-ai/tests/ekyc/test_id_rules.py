"""Luật cấu trúc số định danh: mã tỉnh, thế kỷ + giới tính, năm sinh, mốc hạn thẻ."""

from datetime import date

import pytest

from app.ml.ekyc import id_rules


def test_du_63_ma_tinh_tu_001_den_096():
    codes = id_rules.province_codes()
    assert len(codes) == 63
    assert {"001", "079", "096"} <= codes
    assert "003" not in codes  # mã không tồn tại
    assert all(len(code) == 3 and code.isdigit() for code in codes)


@pytest.mark.parametrize(
    ("id_number", "year", "gender"),
    [
        ("001095012345", 1995, "Nam"),  # 0: 1900–1999 nam
        ("001195012345", 1995, "Nữ"),  # 1: 1900–1999 nữ
        ("079204001234", 2004, "Nam"),  # 2: 2000–2099 nam
        ("079304001234", 2004, "Nữ"),  # 3: 2000–2099 nữ
        ("079401001234", 2101, "Nam"),  # 4: 2100… nam
        ("079501001234", 2101, "Nữ"),  # 5: 2100… nữ
    ],
)
def test_giai_the_ky_gioi_tinh_nam_sinh(id_number, year, gender):
    structure = id_rules.decode_id(id_number)
    assert structure.birth_year == year
    assert structure.gender == gender


def test_khop_ngay_sinh_va_gioi_tinh():
    result = id_rules.check_id("001095012345", date(1995, 3, 15), "Nam")
    assert result.ok
    assert result.warnings == ()


def test_lech_nam_sinh_la_co_mem():
    result = id_rules.check_id("001095012345", date(1996, 3, 15), "Nam")
    assert not result.ok
    assert result.warnings == (id_rules.WARN_STRUCTURE_MISMATCH,)


def test_lech_gioi_tinh_la_co_mem():
    result = id_rules.check_id("001095012345", date(1995, 3, 15), "Nữ")
    assert id_rules.WARN_STRUCTURE_MISMATCH in result.warnings


def test_ma_tinh_la_chi_canh_bao_khong_lam_hong_ok():
    result = id_rules.check_id("003095012345", date(1995, 3, 15), "Nam")
    assert result.ok
    assert result.warnings == (id_rules.WARN_PROVINCE_UNKNOWN,)


@pytest.mark.parametrize("bad", ["", "12345", "0010950123456", "00109501234a", None])
def test_sai_dinh_dang(bad):
    result = id_rules.check_id(bad, None, None)
    assert not result.format_ok
    assert not result.ok


def test_nam_day_du_tu_yy_cua_mrz():
    assert id_rules.full_year(95, "001095012345") == 1995
    assert id_rules.full_year(4, "079204001234") == 2004
    # Không có số định danh: mốc xoay theo năm hiện tại.
    assert id_rules.full_year(95, None, today=date(2026, 10, 16)) == 1995
    assert id_rules.full_year(10, None, today=date(2026, 10, 16)) == 2010


@pytest.mark.parametrize(
    ("dob", "expiry", "ok"),
    [
        (date(1995, 3, 15), date(2020, 3, 15), True),  # 25 tuổi
        (date(1995, 3, 15), date(2035, 3, 15), True),  # 40 tuổi
        (date(1969, 4, 28), date(2029, 4, 28), True),  # 60 tuổi
        (date(2012, 5, 1), date(2026, 5, 1), True),  # 14 tuổi (thẻ 2024)
        (date(1995, 3, 15), date(2034, 3, 15), False),  # không phải mốc
        (date(1995, 3, 15), date(2035, 3, 16), False),  # lệch ngày
        (date(2000, 2, 29), date(2025, 2, 28), True),  # 29/2 sang năm không nhuận
        (None, date(2035, 3, 15), False),
    ],
)
def test_moc_han_the(dob, expiry, ok):
    assert id_rules.expiry_milestone_ok(dob, expiry) is ok
