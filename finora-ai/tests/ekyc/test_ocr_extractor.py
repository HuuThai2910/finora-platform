from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from app.ml.ekyc.ocr_extractor import (
    OcrExtractor,
    find_id_number,
    is_name_like,
    normalize_digit_text,
)


@pytest.fixture
def extractor():
    """Tạo extractor với mocked EasyOCR reader."""
    with patch("app.ml.ekyc.ocr_extractor.easyocr") as mock_easyocr:
        mock_reader = MagicMock()
        mock_easyocr.Reader.return_value = mock_reader
        ext = OcrExtractor()
        ext._reader = mock_reader
        yield ext, mock_reader


def test_extract_success(extractor):
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = [
        ([0, 0, 100, 30], "CĂN CƯỚC CÔNG DÂN", 0.95),
        ([0, 30, 100, 60], "Số / No.: 079204001234", 0.91),
        ([0, 60, 100, 90], "Họ và tên / Full name: NGUYỄN VĂN A", 0.89),
        ([0, 90, 100, 120], "Ngày sinh / Date of birth: 01/01/2000", 0.88),
        ([0, 120, 100, 150], "Giới tính / Sex: Nam", 0.92),
        ([0, 150, 100, 180], "Quê quán: TP Hồ Chí Minh", 0.87),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())
    assert result["id_number"] is not None
    assert result["confidence"] > 0.5


def test_extract_no_text(extractor):
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = []

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())
    assert result["success"] is False


def test_extract_partial_info(extractor):
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = [
        ([0, 0, 100, 30], "079204001234", 0.90),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())
    assert result["id_number"] == "079204001234"


# ── Chuẩn hoá chuỗi số và heuristic họ tên ───────────────────────────


class TestNormalizeDigitText:
    def test_doi_ky_tu_de_nham_thanh_chu_so(self):
        assert normalize_digit_text("O792O4OO1234") == "079204001234"

    def test_bo_dau_phan_cach_giua_cac_chu_so(self):
        assert normalize_digit_text("079 204 001 234") == "079204001234"

    def test_khong_dung_cham_ngoai_chuoi_so(self):
        assert normalize_digit_text("Nguyen A.") == "Nguyen A."


class TestFindIdNumber:
    def test_doc_duoc_so_bi_nham_ky_tu(self):
        assert find_id_number(["So / No,: O79204001234"]) == "079204001234"

    def test_doc_duoc_so_in_thanh_nhom(self):
        assert find_id_number(["Số / No.: 079 204 001 234"]) == "079204001234"

    def test_nam_sinh_dong_truoc_khong_lam_hong_ket_qua(self):
        # Nếu dò trên chuỗi đã nối, "2000" + "079..." dính thành dãy 16 chữ số
        texts = ["Ngày sinh: 01/01/2000", "079 204 001 234"]
        assert find_id_number(texts) == "079204001234"

    def test_khong_co_so_hop_le_tra_none(self):
        assert find_id_number(["CĂN CƯỚC CÔNG DÂN", "12345"]) is None


class TestIsNameLike:
    def test_dong_in_hoa_hai_tu_la_ung_vien(self):
        assert is_name_like("NGUYỄN VĂN A") is True

    def test_dong_tieu_de_phoi_the_bi_loai(self):
        assert is_name_like("CĂN CƯỚC CÔNG DÂN") is False

    def test_dong_co_chu_so_bi_loai(self):
        assert is_name_like("SO 079204001234") is False

    def test_dong_khong_in_hoa_bi_loai(self):
        assert is_name_like("Nguyễn Văn A") is False


def test_extract_name_fallback_khi_nhan_bi_tach_dong(extractor):
    """EasyOCR hay tách nhãn khỏi giá trị — vẫn phải lấy được họ tên."""
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = [
        ([0, 0, 100, 30], "CĂN CƯỚC CÔNG DÂN", 0.95),
        ([0, 30, 100, 60], "079204001234", 0.91),
        ([0, 60, 100, 90], "NGUYỄN VĂN A", 0.89),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())
    assert result["full_name"] == "NGUYỄN VĂN A"
