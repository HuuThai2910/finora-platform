import pytest
import numpy as np
from unittest.mock import patch, MagicMock
from app.ml.ocr_extractor import OcrExtractor


@pytest.fixture
def extractor():
    """Tạo extractor với mocked EasyOCR reader."""
    with patch("app.ml.ocr_extractor.easyocr") as mock_easyocr:
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
