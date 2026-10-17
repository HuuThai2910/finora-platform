"""Định dạng tiền, phần trăm, số đếm, ngày giờ kiểu Việt Nam."""

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import pytest

from app.services.assistant.formatting import (
    ngay_gio_vn,
    ngay_vn,
    phan_tram,
    so_nguyen,
    tien_vnd,
    to_decimal,
)


class TestTienVnd:
    @pytest.mark.parametrize(
        ("value", "expected"),
        [
            (2_350_000, "2.350.000 đ"),
            (0, "0 đ"),
            (999, "999 đ"),
            (1000, "1.000 đ"),
            (Decimal("30000000.00"), "30.000.000 đ"),
            ("12000000", "12.000.000 đ"),
            (" 12000000.5 ", "12.000.001 đ"),
            (2_350_000.5, "2.350.001 đ"),
            (Decimal("2350000.49"), "2.350.000 đ"),
            (-1_500, "-1.500 đ"),
            (Decimal("-0.4"), "0 đ"),
            (1_234_567_890_123, "1.234.567.890.123 đ"),
        ],
    )
    def test_dinh_dang(self, value, expected):
        assert tien_vnd(value) == expected

    @pytest.mark.parametrize("value", [None, "", "abc", "12.000.000", float("nan"), float("inf"), True, [1]])
    def test_gia_tri_khong_doc_duoc(self, value):
        assert tien_vnd(value) == "—"

    def test_so_qua_lon_khong_lam_vo(self):
        assert tien_vnd(Decimal("1e40")) == "—"


class TestPhanTram:
    @pytest.mark.parametrize(
        ("value", "digits", "expected"),
        [
            (14.5, 2, "14,5%"),
            (Decimal("12.00"), 2, "12%"),
            ("18", 2, "18%"),
            (12.345, 2, "12,35%"),
            (12.344, 2, "12,34%"),
            (14.555, 2, "14,56%"),
            (0, 2, "0%"),
            (-1.5, 2, "-1,5%"),
            (-0.001, 2, "0%"),
            (14.5, 0, "15%"),
            (96.125, 1, "96,1%"),
            (1234.5, 2, "1.234,5%"),
        ],
    )
    def test_dinh_dang(self, value, digits, expected):
        assert phan_tram(value, digits) == expected

    def test_mac_dinh_hai_chu_so(self):
        assert phan_tram(Decimal("7.125")) == "7,13%"

    @pytest.mark.parametrize("value", [None, "", "mười"])
    def test_gia_tri_khong_doc_duoc(self, value):
        assert phan_tram(value) == "—"

    def test_digits_am_la_loi_lap_trinh(self):
        with pytest.raises(ValueError):
            phan_tram(1, -1)


class TestSoNguyen:
    @pytest.mark.parametrize(
        ("value", "expected"),
        [(1234, "1.234"), (0, "0"), ("1000000", "1.000.000"), (Decimal("7.5"), "8"), (-12_000, "-12.000"), (None, "—")],
    )
    def test_dinh_dang(self, value, expected):
        assert so_nguyen(value) == expected


class TestToDecimal:
    def test_float_giu_dung_so_nhin_thay(self):
        assert to_decimal(14.555) == Decimal("14.555")

    @pytest.mark.parametrize("value", [None, True, False, "", "  ", "x", object()])
    def test_khong_doc_duoc_thi_none(self, value):
        assert to_decimal(value) is None


class TestNgayVn:
    def test_chuoi_chi_co_ngay_giu_nguyen_ngay(self):
        # LocalDate của Java/Fineract: không đổi múi giờ, nếu không 2026-10-25 có thể lùi một ngày.
        assert ngay_vn("2026-10-25") == "25/10/2026"

    def test_instant_utc_doi_sang_gio_viet_nam(self):
        # 18:30 UTC ngày 25 là 01:30 sáng ngày 26 ở Việt Nam.
        assert ngay_vn("2026-10-25T18:30:00Z") == "26/10/2026"

    def test_chuoi_co_offset(self):
        assert ngay_vn("2026-10-25T23:30:00+07:00") == "25/10/2026"

    def test_chuoi_khong_offset_coi_la_utc(self):
        assert ngay_vn("2026-10-25T18:30:00") == "26/10/2026"

    def test_phan_le_giay_kieu_jackson(self):
        assert ngay_vn("2026-10-15T10:30:00.123456789Z") == "15/10/2026"

    def test_doi_tuong_date_va_datetime(self):
        assert ngay_vn(date(2026, 1, 5)) == "05/01/2026"
        assert ngay_vn(datetime(2026, 1, 5, 20, 0, tzinfo=timezone.utc)) == "06/01/2026"
        assert ngay_vn(datetime(2026, 1, 5, 20, 0, tzinfo=timezone(timedelta(hours=7)))) == "05/01/2026"

    @pytest.mark.parametrize("value", [None, "", "  ", "hôm qua", "2026-13-01", 20261025])
    def test_gia_tri_khong_doc_duoc(self, value):
        assert ngay_vn(value) == "—"


class TestNgayGioVn:
    def test_instant_utc(self):
        assert ngay_gio_vn("2026-10-25T07:30:00Z") == "25/10/2026 14:30"

    def test_qua_nua_dem(self):
        assert ngay_gio_vn("2026-12-31T17:05:00Z") == "01/01/2027 00:05"

    def test_chuoi_chi_co_ngay_tra_phan_ngay(self):
        assert ngay_gio_vn("2026-10-25") == "25/10/2026"

    def test_datetime_khong_tz_coi_la_utc(self):
        naive = datetime(2026, 10, 25, 1, 0)  # noqa: DTZ001 - cố ý không múi giờ để kiểm quy ước UTC
        assert ngay_gio_vn(naive) == "25/10/2026 08:00"

    @pytest.mark.parametrize("value", [None, "", "25/10/2026"])
    def test_gia_tri_khong_doc_duoc(self, value):
        assert ngay_gio_vn(value) == "—"
