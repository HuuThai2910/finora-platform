from unittest.mock import MagicMock, patch

import numpy as np
import pytest

from app.ml.ekyc.ocr_extractor import (
    OcrExtractor,
    find_id_number,
    is_name_like,
    normalize_digit_text,
)


def box(y: int, height: int = 20) -> list[list[int]]:
    """Bbox 4 điểm góc theo format EasyOCR, cho một dòng bắt đầu ở toạ độ y."""
    return [[0, y], [100, y], [100, y + height], [0, y + height]]


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
        (box(0), "CĂN CƯỚC CÔNG DÂN", 0.95),
        (box(30), "Số / No.: 079204001234", 0.91),
        (box(60), "Họ và tên / Full name: NGUYỄN VĂN A", 0.89),
        (box(90), "Ngày sinh / Date of birth: 01/01/2000", 0.88),
        (box(120), "Giới tính / Sex: Nam", 0.92),
        (box(150), "Quê quán: TP Hồ Chí Minh", 0.87),
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
        (box(0), "079204001234", 0.90),
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
        (box(0), "CĂN CƯỚC CÔNG DÂN", 0.95),
        (box(30), "079204001234", 0.91),
        (box(60), "NGUYỄN VĂN A", 0.89),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())
    assert result["full_name"] == "NGUYỄN VĂN A"


def test_extract_du_truong_mem_ke_ca_dia_chi(extractor):
    """Nhãn mất dấu, giá trị tách dòng, địa chỉ tràn hai dòng — vẫn trích đủ."""
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = [
        (box(0), "CONG HOA XA HOI CHU NGHIA VIET NAM", 0.95),
        (box(20), "So / No.: 079204001234", 0.91),
        (box(40), "Ho va ten / Full name:", 0.89),
        (box(60), "NGUYEN THI B", 0.90),
        (box(80), "Ngay sinh / Date of birth: 02/03/1999", 0.88),
        (box(100), "Gioi tinh / Sex: Nu", 0.92),
        (box(120), "Que quan / Place of origin:", 0.87),
        (box(140), "Nam Dinh", 0.86),
        (box(160), "Noi thuong tru / Place of residence:", 0.85),
        (box(180), "25 Nguyen Trai", 0.84),
        (box(200), "Thanh Xuan, Ha Noi", 0.83),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())

    assert result["date_of_birth"] == "02/03/1999"
    assert result["gender"] == "Nữ"
    assert result["place_of_origin"] == "Nam Dinh"
    assert result["address"] == "25 Nguyen Trai Thanh Xuan, Ha Noi"


def test_gioi_tinh_khong_dinh_chu_nam_trong_viet_nam(extractor):
    """"Nam" trong tiêu đề/quốc tịch không được tính là giới tính."""
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = [
        (box(0), "CONG HOA XA HOI CHU NGHIA VIET NAM", 0.95),
        (box(20), "079204001234", 0.91),
        (box(40), "Quoc tich / Nationality: Viet Nam", 0.90),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())

    assert result["gender"] is None


def test_ngay_sinh_khong_nham_sang_han_the(extractor):
    """Ngày trên dòng "Có giá trị đến" không được lấy làm ngày sinh."""
    ext, mock_reader = extractor
    mock_reader.readtext.return_value = [
        (box(0), "Co gia tri den / Date of expiry: 01/01/2030", 0.90),
        (box(20), "079204001234", 0.91),
        (box(40), "Ngay sinh / Date of birth: 02/03/1999", 0.88),
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())

    assert result["date_of_birth"] == "02/03/1999"


def test_anh_kho_doc_duoc_vot_bang_luot_phan_giai_cao(extractor):
    """Lượt đầu thiếu trường mềm → lượt phân giải cao vớt đúng trường thiếu."""
    ext, mock_reader = extractor
    mock_reader.readtext.side_effect = [
        # Lượt chuẩn: chỉ ra số và tên
        [
            (box(0), "079204001234", 0.5),
            (box(30), "NGUYEN VAN A", 0.5),
        ],
        # Lượt phân giải cao: đọc thêm được các dòng chữ nhỏ
        [
            (box(0), "Ngay sinh / Date of birth: 02/03/1999", 0.8),
            (box(30), "Gioi tinh / Sex: Nu", 0.8),
            (box(60), "Noi thuong tru / Place of residence: 25 Nguyen Trai, Ha Noi", 0.8),
        ],
    ]

    dummy_image = np.zeros((300, 400, 3), dtype=np.uint8)
    import cv2
    _, buf = cv2.imencode(".jpg", dummy_image)
    result = ext.extract(buf.tobytes())

    assert result["date_of_birth"] == "02/03/1999"
    assert result["gender"] == "Nữ"
    assert result["address"] == "25 Nguyen Trai, Ha Noi"


class TestChiuLoiOcrThucTe:
    def test_ngay_co_khoang_trang_va_ky_tu_nham(self):
        from app.ml.ekyc.ocr_extractor import find_date
        assert find_date("Ngay sinh: 24 / 08 / 2004") == "24/08/2004"
        assert find_date("Ngay sinh: 24/O8/2OO4") == "24/08/2004"
        assert find_date("khong co ngay") is None

    def test_nhan_bi_doc_sai_mot_ky_tu_van_khop(self):
        from app.ml.ekyc.ocr_extractor import LABELS_ADDRESS, LABELS_GENDER, find_label_end
        # "gioi tinh" bị đọc thành "gio1 tinh" — vẫn phải nhận ra nhãn
        assert find_label_end("gio1 tinh sex", LABELS_GENDER) > 0
        # "noi thuong tru" bị đọc thành "noi thuong tro"
        assert find_label_end("noi thuong tro 202 a", LABELS_ADDRESS) > 0

    def test_nhan_ngan_khong_duoc_khop_mo(self):
        from app.ml.ekyc.ocr_extractor import LABELS_GENDER, find_label_end
        # "sex" (nhãn ngắn) không được khớp mờ sang từ khác như "sea"/"5ex" lung tung
        assert find_label_end("sea view", LABELS_GENDER) < 0


def test_thieu_easyocr_phai_no_loi_ha_tang():
    """Thiếu thư viện phải nổ RuntimeError để backend trả AI_UNAVAILABLE.

    Nuốt lỗi thành success=False sẽ hiển thị "ảnh mờ, chụp lại" — người dùng
    chụp lại bao nhiêu lần cũng vô ích vì lỗi nằm ở môi trường server.
    """
    with patch("app.ml.ekyc.ocr_extractor.easyocr", None):
        ext = OcrExtractor()
        with pytest.raises(RuntimeError):
            ext.extract(b"anh-bat-ky")
