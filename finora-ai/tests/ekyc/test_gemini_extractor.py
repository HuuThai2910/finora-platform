"""Kiểm tra engine đọc CCCD qua Gemini — mock client, không gọi mạng."""

import json
from unittest.mock import MagicMock, patch

from app.ml.ekyc.gemini_extractor import GeminiOcrExtractor, build_from_env


def fake_client(payload: dict) -> MagicMock:
    client = MagicMock()
    client.models.generate_content.return_value = MagicMock(text=json.dumps(payload))
    return client


def test_doc_du_truong_va_chuan_hoa():
    client = fake_client({
        "id_number": "036 094 001 234",     # model trả có khoảng trắng — phải làm sạch
        "full_name": "Nguyễn Văn A",         # phải ép IN HOA
        "date_of_birth": "24/8/2004",        # thiếu số 0 — phải chuẩn hoá dd/mm/yyyy
        "gender": "Nam",
        "place_of_origin": "Phường 2, Gò Công, Tiền Giang",
        "address": "202 A, Đường 12, KP5, Phường 2, Gò Công, Tiền Giang",
        "confidence": 0.97,
    })

    result = GeminiOcrExtractor(client).extract(b"anh")

    assert result["success"] is True
    assert result["id_number"] == "036094001234"
    assert result["full_name"] == "NGUYỄN VĂN A"
    assert result["date_of_birth"] == "24/08/2004"
    assert result["gender"] == "Nam"
    assert result["address"].startswith("202 A")
    assert result["confidence"] == 0.97


def test_so_cccd_khong_hop_le_thi_success_false():
    client = fake_client({"id_number": "12345", "confidence": 0.5})

    result = GeminiOcrExtractor(client).extract(b"anh")

    assert result["success"] is False
    assert result["id_number"] is None


def test_gia_tri_la_bi_loai_thay_vi_giu_chuoi_rac():
    client = fake_client({
        "id_number": "036094001234",
        "date_of_birth": "khong ro",
        "gender": "Khac",
        "confidence": 2.5,  # ngoài khoảng — phải kẹp về 1.0
    })

    result = GeminiOcrExtractor(client).extract(b"anh")

    assert result["date_of_birth"] is None
    assert result["gender"] is None
    assert result["confidence"] == 1.0


def test_khong_co_key_thi_khong_tao_engine():
    with patch.dict("os.environ", {}, clear=True):
        assert build_from_env() is None
