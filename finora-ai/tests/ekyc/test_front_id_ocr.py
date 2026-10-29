"""Gắn hai mặt thẻ: tìm số định danh đã biết trong chữ số in ở mặt trước."""

import cv2
import pytest

from app.ml.ekyc import front_id_ocr, tesseract_ocr

from .synthetic import front_card

KNOWN = "001095012345"


@pytest.mark.parametrize(
    ("lines", "found"),
    [
        (["So / No.: 001095012345"], True),
        (["001 095 012 345"], True),  # Tesseract tách nhóm
        (["00109502345"], True),  # mất một chữ số
        (["0010950123456"], True),  # thừa một chữ số
        (["001095012845"], True),  # sai một chữ số
        (["001095018845"], False),  # sai hai chữ số
        (["15031995", "15032035"], False),  # chỉ có ngày tháng
        ([], False),
    ],
)
def test_tim_so_da_biet(lines, found):
    assert front_id_ocr.find_known_id(lines, KNOWN, max_edits=1) is found


def test_khong_cho_lech_khi_max_edits_0():
    assert not front_id_ocr.find_known_id(["001095012845"], KNOWN, max_edits=0)
    assert front_id_ocr.find_known_id(["x001095012345y"], KNOWN, max_edits=0)


def test_khoang_cach_sua_doi():
    assert front_id_ocr.edit_distance("123", "123", 1) == 0
    assert front_id_ocr.edit_distance("123", "124", 1) == 1
    assert front_id_ocr.edit_distance("123", "1234", 1) == 1
    assert front_id_ocr.edit_distance("123", "456", 1) == 2  # cắt sớm ở limit + 1


def test_tesseract_tim_so_tren_mat_truoc_tong_hop():
    if not tesseract_ocr.available():
        pytest.skip("Máy không có Tesseract")
    card = front_card(None)
    cv2.putText(card, KNOWN, (600, 230), cv2.FONT_HERSHEY_SIMPLEX, 1.6, (0, 0, 200), 3)
    assert front_id_ocr.front_shows_id(card, KNOWN, 1, timeout_s=20) is True


def test_khong_co_tesseract_thi_khong_ket_luan(monkeypatch):
    monkeypatch.setattr(tesseract_ocr, "available", lambda: False)
    assert front_id_ocr.front_shows_id(front_card(None), KNOWN, 1, timeout_s=1) is None
