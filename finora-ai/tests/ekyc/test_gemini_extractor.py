"""Gemini thu hẹp: client giả lập hoàn toàn, không bao giờ gọi mạng."""

import json
from dataclasses import replace
from datetime import date
from unittest.mock import MagicMock

import pytest
from google.genai import errors as genai_errors

from app.ml.ekyc import gemini_extractor
from app.ml.ekyc.gemini_extractor import (
    FrontFieldsOptionalSchema,
    FrontFieldsSchema,
    GeminiCardReader,
    build_from_env,
)
from app.ml.ekyc.thresholds import EkycSettings


def client_returning(payload) -> MagicMock:
    client = MagicMock()
    text = (
        payload if isinstance(payload, str) or payload is None else json.dumps(payload)
    )
    client.models.generate_content.return_value = MagicMock(text=text)
    return client


def _config_of(client: MagicMock):
    return client.models.generate_content.call_args.kwargs["config"]


class TestFrontFields:
    def test_chuan_hoa_ket_qua(self):
        client = client_returning(
            {
                "card_title": "CĂN CƯỚC CÔNG DÂN",
                "id_number": "001 095 012 345",
                "full_name": "Nguyễn  Văn A",
                "date_of_birth": "15/3/1995",
                "gender": "nam",
                "address": "Số 1  Hàng Bạc,\nHà Nội",
                "expiry_date": "Không thời hạn",
            }
        )
        result = GeminiCardReader(client).read_front_fields(
            b"jpeg", want_optional=False
        )
        assert result.id_number == "001095012345"
        assert result.full_name == "NGUYỄN VĂN A"
        assert result.date_of_birth == date(1995, 3, 15)
        assert result.gender == "Nam"
        assert result.address == "Số 1 Hàng Bạc, Hà Nội"
        assert result.no_expiry and result.expiry_date is None
        assert _config_of(client).response_schema is FrontFieldsSchema

    def test_truong_tuy_chon_chi_hoi_khi_bat(self):
        client = client_returning(
            {"place_of_origin": "Gò Công", "id_number": "123456789"}
        )
        result = GeminiCardReader(client).read_front_fields(b"jpeg", want_optional=True)
        assert result.place_of_origin == "Gò Công"
        assert result.id_number == "123456789"  # 9 số CMND vẫn giữ để nhận diện CMND
        assert _config_of(client).response_schema is FrontFieldsOptionalSchema

    def test_gia_tri_rac_bi_loai(self):
        client = client_returning(
            {"id_number": "12-34", "date_of_birth": "khong ro", "gender": "Khac"}
        )
        result = GeminiCardReader(client).read_front_fields(
            b"jpeg", want_optional=False
        )
        assert (result.id_number, result.date_of_birth, result.gender) == (
            None,
            None,
            None,
        )

    def test_gui_anh_jpeg_va_dung_model_cau_hinh(self):
        client = client_returning({})
        GeminiCardReader(client, model="gemini-test").read_front_fields(
            b"anh-da-che", False
        )
        kwargs = client.models.generate_content.call_args.kwargs
        assert kwargs["model"] == "gemini-test"
        part = kwargs["contents"][0]
        assert part.inline_data.data == b"anh-da-che"
        assert part.inline_data.mime_type == "image/jpeg"
        assert "che đen" in kwargs["contents"][1]  # prompt nói rõ phần chân dung đã che

    def test_thinking_chi_tat_voi_ho_2_5(self):
        old = client_returning({})
        GeminiCardReader(old, model="gemini-2.5-flash").read_front_id(b"x")
        assert _config_of(old).thinking_config.thinking_budget == 0
        new = client_returning({})
        GeminiCardReader(new, model="gemini-3.5-flash-lite").read_front_id(b"x")
        assert _config_of(new).thinking_config is None


class TestFailures:
    def test_text_none_khi_bi_chan_thi_tra_none(self):
        reader = GeminiCardReader(client_returning(None))
        assert reader.read_front_fields(b"x", False) is None
        assert reader.read_mrz_lines(b"x") is None
        assert reader.read_front_id(b"x") is None

    def test_loi_429_het_quota_khong_lam_hong_request(self):
        client = MagicMock()
        client.models.generate_content.side_effect = genai_errors.ClientError(
            429,
            {
                "error": {
                    "code": 429,
                    "message": "quota",
                    "status": "RESOURCE_EXHAUSTED",
                }
            },
        )
        assert GeminiCardReader(client).read_front_fields(b"x", False) is None

    @pytest.mark.parametrize(
        "error", [TimeoutError("het gio"), RuntimeError("mang"), ValueError("la")]
    )
    def test_moi_loi_khac_cung_tra_none(self, error):
        client = MagicMock()
        client.models.generate_content.side_effect = error
        assert GeminiCardReader(client).read_mrz_lines(b"x") is None

    def test_json_sai_khuon(self):
        assert (
            GeminiCardReader(client_returning("{khong phai json")).read_front_id(b"x")
            is None
        )


class TestMrzAndPrintedId:
    def test_doc_ba_dong_mrz(self):
        client = client_returning(
            {"line1": "IDVNM...", "line2": "950315...", "line3": "NGUYEN"}
        )
        assert GeminiCardReader(client).read_mrz_lines(b"x") == [
            "IDVNM...",
            "950315...",
            "NGUYEN",
        ]

    def test_thieu_dong_mrz_thi_none(self):
        client = client_returning({"line1": "IDVNM...", "line2": None})
        assert GeminiCardReader(client).read_mrz_lines(b"x") is None

    @pytest.mark.parametrize(
        ("raw", "expected"),
        [
            ("001095012345", "001095012345"),
            ("001.095.012.345", "001095012345"),
            ("123456789", "123456789"),
            ("12345", None),
            (None, None),
        ],
    )
    def test_so_in_mat_truoc(self, raw, expected):
        client = client_returning({"id_number": raw})
        assert GeminiCardReader(client).read_front_id(b"x") == expected


class TestBuildFromEnv:
    def test_che_do_off(self, monkeypatch):
        monkeypatch.setenv("GEMINI_API_KEY", "khoa-gia")
        assert build_from_env(EkycSettings(gemini_mode="off")) is None

    def test_khong_co_key(self, monkeypatch):
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        monkeypatch.delenv("GOOGLE_API_KEY", raising=False)
        assert build_from_env(EkycSettings()) is None

    def test_co_key_thi_tao_client_co_timeout(self, monkeypatch):
        fake_genai = MagicMock()
        monkeypatch.setattr(gemini_extractor, "genai", fake_genai)
        monkeypatch.setenv("GEMINI_API_KEY", "khoa-gia")
        monkeypatch.setenv("GEMINI_OCR_MODEL", "gemini-test")
        reader = build_from_env(replace(EkycSettings(), gemini_timeout_ms=12345))
        assert isinstance(reader, GeminiCardReader)
        assert reader.model == "gemini-test"
        kwargs = fake_genai.Client.call_args.kwargs
        assert kwargs["api_key"] == "khoa-gia"
        assert kwargs["http_options"].timeout == 12345

    def test_thieu_sdk(self, monkeypatch):
        monkeypatch.setattr(gemini_extractor, "genai", None)
        monkeypatch.setenv("GEMINI_API_KEY", "khoa-gia")
        assert build_from_env(EkycSettings()) is None
