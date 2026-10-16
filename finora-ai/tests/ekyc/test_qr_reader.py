"""QR CCCD: parse 7 trường, sửa lỗi mã từng trường, giải end-to-end bằng zxing-cpp."""

from datetime import date

import cv2
import pytest

from app.ml.ekyc import qr_reader

from .synthetic import SYNTHETIC_QR, front_card, on_background


class TestParse:
    def test_du_7_truong(self):
        payload = qr_reader.parse_qr_text(SYNTHETIC_QR)
        assert payload is not None
        assert payload.id_number == "001095012345"
        assert payload.old_id_number == "123456789"
        assert payload.full_name == "NGUYỄN VĂN A"  # giữ dấu, IN HOA
        assert payload.date_of_birth == date(1995, 3, 15)
        assert payload.gender == "Nam"
        assert payload.address.startswith("Số 1 phố Hàng Bạc")
        assert payload.issue_date == date(2021, 7, 1)
        assert not payload.text_dropped

    def test_cmnd_rong_va_truong_thu_8_tro_di_bi_bo_qua(self):
        text = "079304001234||Trần Thị Bé|01022004|Nữ|Q1, TP HCM|02032022|x|Cha|Mẹ"
        payload = qr_reader.parse_qr_text(text)
        assert payload.old_id_number is None
        assert payload.gender == "Nữ"
        assert payload.issue_date == date(2022, 3, 2)

    @pytest.mark.parametrize(
        "text",
        [
            None,
            "",
            "khong co dau gach",
            "12345|x|y|15031995|Nam",  # số không đủ 12 chữ số
            "001095012345||A|15/03/1995|Nam",  # QR ví Zalo: ngày có dấu '/'
            "001095012345||A|31021995|Nam",  # ngày không tồn tại
            "001095012345||A",  # thiếu trường
        ],
    )
    def test_sai_khuon_tra_none(self, text):
        assert qr_reader.parse_qr_text(text) is None


class TestMojibake:
    @pytest.mark.parametrize("encoding", ["latin-1", "cp1252", "shift_jis", "big5"])
    def test_sua_duoc_chu_giai_nham_bang_ma(self, encoding):
        candidates = ["Trần Bùi Lý", "Phạm Võ Huỳnh", "Lê Ngô Vũ", "Nguyễn Văn Hùng"]
        tried = 0
        for original in candidates:
            try:
                broken = original.encode("utf-8").decode(encoding)
            except UnicodeDecodeError:
                continue
            tried += 1
            assert qr_reader.looks_broken(broken)
            assert qr_reader.repair_text(broken) == original
        assert tried > 0, f"không dựng được mẫu lỗi mã cho {encoding}"

    def test_chu_dung_khong_bi_doi(self):
        for text in ["Cà Mau", "Hà Nội", "Đặng Thị Ánh Tuyết", "Số 12, Ấp Mỹ Lợi"]:
            assert not qr_reader.looks_broken(text)
            assert qr_reader.repair_text(text) == text

    def test_payload_loi_ma_ca_chuoi_van_doc_duoc_ten_dia_chi(self):
        broken = SYNTHETIC_QR.encode("utf-8").decode("latin-1")
        payload = qr_reader.parse_qr_text(broken)
        assert payload.full_name == "NGUYỄN VĂN A"
        assert payload.address.startswith("Số 1 phố Hàng Bạc")
        assert payload.gender == "Nam"

    def test_khong_sua_duoc_thi_bo_ten_nhung_giu_so(self):
        # Ký tự thay thế U+FFFD: thông tin đã mất, không khôi phục được.
        broken = "001095012345||Nguy\ufffdn V\ufffdn A|15031995|N\ufffd|\ufffd\ufffd|01072021"
        payload = qr_reader.parse_qr_text(broken)
        assert payload is not None
        assert payload.id_number == "001095012345"
        assert payload.full_name is None
        assert payload.address is None
        assert payload.gender is None
        assert payload.text_dropped

    def test_bytes_tho_utf8_duoc_uu_tien(self):
        raw = SYNTHETIC_QR.encode("utf-8")
        assert qr_reader.text_from_barcode(raw, "chuoi da giai sai") == SYNTHETIC_QR
        assert qr_reader.text_from_barcode(b"\xff\xfe", "du phong") == "du phong"


class TestDecodeImage:
    def test_giai_qr_tren_the_tong_hop(self):
        read = qr_reader.decode_qr([front_card(SYNTHETIC_QR)])
        assert read is not None and read.payload is not None
        assert read.payload.full_name == "NGUYỄN VĂN A"

    def test_giai_qr_tren_the_xoay_tren_nen_va_hoi_mo(self):
        scene = on_background(front_card(SYNTHETIC_QR), angle=14.0)
        mild_blur = cv2.GaussianBlur(scene, (0, 0), 1.2)
        read = qr_reader.decode_qr([mild_blur])
        assert read is not None and read.payload is not None
        assert read.payload.id_number == "001095012345"
        assert read.payload.address.startswith("Số 1 phố Hàng Bạc")

    def test_qr_nho_can_goc_phan_tu_hoac_phong_to(self):
        card = front_card(SYNTHETIC_QR)
        small = cv2.resize(card, None, fx=0.45, fy=0.45, interpolation=cv2.INTER_AREA)
        read = qr_reader.decode_qr([small])
        assert read is not None and read.payload is not None

    def test_khong_co_qr(self):
        assert qr_reader.decode_qr([front_card(None)]) is None

    def test_qr_khong_phai_cccd_van_bao_la_thay_qr(self):
        read = qr_reader.decode_qr([front_card("https://example.com/khong-phai-cccd")])
        assert read is not None
        assert read.payload is None
