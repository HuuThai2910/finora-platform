"""Gemini chỉ **điền chỗ trống** khi đọc CCCD — không bao giờ là nguồn quyết định.

Thu hẹp so với bản cũ (gửi nguyên ảnh mặt trước, tin ``confidence`` tự khai):

- chỉ nhận **crop đã che** 38% bên trái mặt trước (vùng chân dung) và đã tô đen mọi
  khuôn mặt dò được — không bao giờ gửi khuôn mặt (dữ liệu sinh trắc học);
- hoặc **riêng dải MRZ** mặt sau — kết quả chỉ được nhận khi qua đủ check digit
  (``mrz.best_of``), nên một chữ số bịa sẽ lộ ra;
- hoặc hỏi riêng **số định danh in ở mặt trước** để gắn hai mặt thẻ.

Gemini lỗi, hết quota (429), bị chặn an toàn (``response.text is None``) hay quá thời
gian đều trả ``None``: request vẫn chạy tiếp bằng nguồn tất định, thiếu trường thì hồ
sơ đi duyệt tay — không bao giờ làm hỏng cả request.

Quyền riêng tư: điều khoản gói free của Gemini API cấm gửi dữ liệu cá nhân ("Do not
submit sensitive, confidential, or personal information to the Unpaid Services"). Chỉ
bật với gói trả phí, hoặc với thẻ mẫu/thẻ của người đã đồng ý khi demo. API key chỉ
đọc từ biến môi trường.
"""

from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import dataclass
from datetime import date

from pydantic import BaseModel, ValidationError

from app.ml.ekyc.thresholds import GEMINI_MODE_OFF, EkycSettings, settings
from app.ml.ekyc.vn_text import (
    clean_display_text,
    normalize_gender,
    parse_display_date,
    strip_diacritics,
)

try:
    from google import genai
    from google.genai import types as genai_types
except ImportError:  # SDK chưa cài — build_from_env trả None, pipeline chạy không có AI
    genai = None  # type: ignore[assignment]
    genai_types = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

#: Đổi qua ``GEMINI_OCR_MODEL``. Project mới nên chọn model trang deprecations khuyên dùng.
DEFAULT_MODEL = "gemini-2.5-flash"

_MASK_NOTE = (
    "Ảnh là mặt trước thẻ căn cước Việt Nam; phần bên trái (ảnh chân dung) đã bị cố ý "
    "che đen, không suy đoán phần bị che. "
)
PROMPT_FRONT = (
    _MASK_NOTE
    + "Chỉ chép lại chữ in nhìn thấy rõ, điền JSON theo schema. card_title là dòng tiêu đề "
    "in hoa (ví dụ 'CĂN CƯỚC CÔNG DÂN', 'CĂN CƯỚC', 'CHỨNG MINH NHÂN DÂN'). id_number là số "
    "ở mục 'Số' hoặc 'Số định danh cá nhân', chỉ gồm chữ số. full_name là 'Họ và tên' (hoặc "
    "'Họ, chữ đệm và tên khai sinh') có dấu. date_of_birth dạng dd/mm/yyyy. gender chỉ 'Nam' "
    "hoặc 'Nữ'. address là 'Nơi thường trú' gộp thành một dòng. issue_date/expiry_date dạng "
    "dd/mm/yyyy nếu in trên phần nhìn thấy; expiry_date ghi 'Không thời hạn' nếu thẻ in như "
    "vậy. Trường không có trên ảnh hoặc không đọc chắc chắn thì để null, tuyệt đối không bịa."
)
PROMPT_FRONT_OPTIONAL = (
    " place_of_origin là 'Quê quán'; birth_registration_place là 'Nơi đăng ký khai sinh' "
    "(chỉ khi có in trên ảnh)."
)
PROMPT_MRZ = (
    "Ảnh là dải ký tự máy đọc (MRZ, chuẩn ICAO TD1) ở cuối mặt sau thẻ căn cước Việt Nam. "
    "Chép chính xác 3 dòng, mỗi dòng đúng 30 ký tự, chỉ gồm A-Z, 0-9 và '<' (giữ nguyên mọi "
    "ký tự '<'). Không sửa, không đoán ký tự mờ; nếu không thấy MRZ thì để null."
)
PROMPT_FRONT_ID = (
    _MASK_NOTE
    + "Chỉ trả về số in ở mục 'Số' hoặc 'Số định danh cá nhân' (chỉ gồm chữ số); không đọc "
    "rõ thì để null, tuyệt đối không bịa."
)

_RE_NON_DIGIT = re.compile(r"\D")


class FrontFieldsSchema(BaseModel):
    card_title: str | None = None
    id_number: str | None = None
    full_name: str | None = None
    date_of_birth: str | None = None
    gender: str | None = None
    address: str | None = None
    issue_date: str | None = None
    expiry_date: str | None = None


class FrontFieldsOptionalSchema(FrontFieldsSchema):
    place_of_origin: str | None = None
    birth_registration_place: str | None = None


class MrzSchema(BaseModel):
    line1: str | None = None
    line2: str | None = None
    line3: str | None = None


class PrintedIdSchema(BaseModel):
    id_number: str | None = None


@dataclass(frozen=True)
class FrontRead:
    """Trường Gemini đọc từ mặt trước đã che, đã chuẩn hoá."""

    card_title: str | None = None
    id_number: str | None = None
    full_name: str | None = None
    date_of_birth: date | None = None
    gender: str | None = None
    address: str | None = None
    place_of_origin: str | None = None
    birth_registration_place: str | None = None
    issue_date: date | None = None
    expiry_date: date | None = None
    no_expiry: bool = False


def normalize_id(text: str | None) -> str | None:
    """Chỉ giữ chữ số; nhận 12 số (CCCD/Căn cước) hoặc 9 số (CMND)."""
    digits = _RE_NON_DIGIT.sub("", text or "")
    return digits if len(digits) in (9, 12) else None


def _text(value: str | None) -> str | None:
    cleaned = clean_display_text(value or "")
    return cleaned or None


def _normalize_front(fields: FrontFieldsSchema) -> FrontRead:
    expiry_text = fields.expiry_date or ""
    no_expiry = "KHONG THOI HAN" in strip_diacritics(expiry_text).upper()
    name = _text(fields.full_name)
    return FrontRead(
        card_title=_text(fields.card_title),
        id_number=normalize_id(fields.id_number),
        full_name=name.upper() if name else None,
        date_of_birth=parse_display_date(fields.date_of_birth),
        gender=normalize_gender(fields.gender),
        address=_text(fields.address),
        place_of_origin=_text(getattr(fields, "place_of_origin", None)),
        birth_registration_place=_text(
            getattr(fields, "birth_registration_place", None)
        ),
        issue_date=parse_display_date(fields.issue_date),
        expiry_date=None if no_expiry else parse_display_date(fields.expiry_date),
        no_expiry=no_expiry,
    )


class GeminiCardReader:
    """Ba lời gọi Gemini hẹp; mọi lỗi đều quy về ``None`` (coi như AI không có)."""

    def __init__(self, client, model: str = DEFAULT_MODEL):
        self._client = client
        self._model = model

    @property
    def model(self) -> str:
        return self._model

    def _config(self, schema: type[BaseModel]):
        kwargs = {
            "temperature": 0,
            "response_mime_type": "application/json",
            "response_schema": schema,
        }
        # Tắt "thinking" chỉ với họ 2.5 (đọc chữ không cần suy luận dài); họ mới hơn
        # có thể không nhận tham số này nên để mặc định của model.
        if self._model.startswith("gemini-2.5"):
            kwargs["thinking_config"] = genai_types.ThinkingConfig(thinking_budget=0)
        return genai_types.GenerateContentConfig(**kwargs)

    def _generate(self, jpeg: bytes, prompt: str, schema: type[BaseModel], task: str):
        try:
            response = self._client.models.generate_content(
                model=self._model,
                contents=[
                    genai_types.Part.from_bytes(data=jpeg, mime_type="image/jpeg"),
                    prompt,
                ],
                config=self._config(schema),
            )
        except Exception as exc:  # noqa: BLE001 — 429/quota/timeout/mạng: coi như AI không có
            code = getattr(exc, "code", None)
            logger.warning(
                "Gemini %s lỗi (%s%s) — bỏ qua AI.",
                task,
                type(exc).__name__,
                f" {code}" if code else "",
            )
            return None
        text = getattr(response, "text", None)
        if not text:
            logger.warning(
                "Gemini %s không trả nội dung (bị chặn hoặc rỗng) — bỏ qua AI.", task
            )
            return None
        try:
            return schema.model_validate(json.loads(text))
        except (json.JSONDecodeError, ValidationError, TypeError):
            logger.warning("Gemini %s trả JSON sai khuôn — bỏ qua AI.", task)
            return None

    def read_front_fields(
        self, masked_front_jpeg: bytes, want_optional: bool
    ) -> FrontRead | None:
        """Đọc các trường in ở mặt trước đã che chân dung."""
        schema = FrontFieldsOptionalSchema if want_optional else FrontFieldsSchema
        prompt = PROMPT_FRONT + (PROMPT_FRONT_OPTIONAL if want_optional else "")
        parsed = self._generate(masked_front_jpeg, prompt, schema, "front_fields")
        if parsed is None:
            return None
        result = _normalize_front(parsed)
        logger.info(
            "Gemini front_fields: id=%s name=%s dob=%s gender=%s address=%s title=%s",
            result.id_number is not None,
            result.full_name is not None,
            result.date_of_birth is not None,
            result.gender is not None,
            result.address is not None,
            result.card_title is not None,
        )
        return result

    def read_mrz_lines(self, mrz_band_jpeg: bytes) -> list[str] | None:
        """3 dòng MRZ thô; bên gọi tự kiểm check digit trước khi nhận."""
        parsed = self._generate(mrz_band_jpeg, PROMPT_MRZ, MrzSchema, "mrz")
        if parsed is None or not parsed.line1 or not parsed.line2:
            return None
        return [parsed.line1, parsed.line2, parsed.line3 or ""]

    def read_front_id(self, masked_front_jpeg: bytes) -> str | None:
        """Số định danh in ở mặt trước (để gắn với số đọc từ mặt sau)."""
        parsed = self._generate(
            masked_front_jpeg, PROMPT_FRONT_ID, PrintedIdSchema, "front_id"
        )
        return normalize_id(parsed.id_number) if parsed is not None else None


def build_from_env(cfg: EkycSettings | None = None) -> GeminiCardReader | None:
    """Tạo reader nếu ``EKYC_GEMINI_MODE`` khác ``off``, có key và có SDK; ngược lại ``None``."""
    cfg = cfg or settings()
    if cfg.gemini_mode == GEMINI_MODE_OFF:
        logger.info("EKYC_GEMINI_MODE=off — đọc CCCD chỉ bằng nguồn tất định.")
        return None
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        logger.info("Không có GEMINI_API_KEY — đọc CCCD chỉ bằng nguồn tất định.")
        return None
    if genai is None:
        logger.warning("Có GEMINI_API_KEY nhưng chưa cài google-genai — bỏ qua AI.")
        return None
    model = os.getenv("GEMINI_OCR_MODEL", DEFAULT_MODEL).strip() or DEFAULT_MODEL
    client = genai.Client(
        api_key=api_key,
        http_options=genai_types.HttpOptions(timeout=cfg.gemini_timeout_ms),
    )
    logger.info(
        "Gemini (%s) bật ở chế độ fallback, timeout %d ms.",
        model,
        cfg.gemini_timeout_ms,
    )
    return GeminiCardReader(client, model)
