"""Đọc CCCD bằng Gemini vision — bền với ảnh mờ/loá hơn hẳn OCR truyền thống.

Đánh đổi cần biết: ảnh giấy tờ (PII) được gửi tới API của Google. Chấp nhận
được cho môi trường demo/khoá luận; môi trường thật phải có đánh giá pháp lý
riêng. API key chỉ đọc từ biến môi trường, không bao giờ nằm trong code/repo.
"""

import json
import logging
import os
import re

from pydantic import BaseModel

try:
    from google import genai
    from google.genai import types as genai_types
except ImportError:  # SDK chưa cài — service sẽ tự rơi về EasyOCR
    genai = None  # type: ignore[assignment]
    genai_types = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

#: Đổi model qua env khi cần (ví dụ nâng lên bản mới hơn) mà không sửa code.
DEFAULT_MODEL = "gemini-2.5-flash"

RE_ID_NUMBER = re.compile(r"^0\d{11}$")
RE_DATE = re.compile(r"^(\d{1,2})\s*[/\-.]\s*(\d{1,2})\s*[/\-.]\s*(\d{4})$")

PROMPT = (
    "Ảnh là mặt trước thẻ Căn cước công dân Việt Nam. Đọc ảnh và điền JSON theo schema. "
    "Quy tắc: id_number là trường 'Số / No.', đúng 12 chữ số, không khoảng trắng. "
    "date_of_birth là 'Ngày sinh' dạng dd/mm/yyyy — TUYỆT ĐỐI không lấy ngày ở 'Có giá trị đến'. "
    "gender chỉ nhận 'Nam' hoặc 'Nữ'. place_of_origin là 'Quê quán'. "
    "address là 'Nơi thường trú', gộp toàn bộ các dòng thành một chuỗi. "
    "full_name là 'Họ và tên' viết IN HOA có dấu. "
    "Trường nào không đọc được thì để null, không được bịa. "
    "confidence là độ tự tin tổng thể 0–1."
)


class OcrFields(BaseModel):
    """Schema bắt Gemini trả JSON đúng khuôn — khớp OcrResponse của service."""

    id_number: str | None = None
    full_name: str | None = None
    date_of_birth: str | None = None
    gender: str | None = None
    place_of_origin: str | None = None
    address: str | None = None
    confidence: float = 0.9


def build_from_env():
    """Tạo extractor nếu có key trong env; thiếu key/SDK thì trả ``None``.

    Trả ``None`` thay vì ném lỗi để service rơi về EasyOCR — chạy offline
    không có key vẫn phải hoạt động.
    """
    api_key = os.getenv("GEMINI_API_KEY") or os.getenv("GOOGLE_API_KEY")
    if not api_key:
        return None
    if genai is None:
        logger.warning("Có GEMINI_API_KEY nhưng chưa cài google-genai — dùng EasyOCR.")
        return None
    model = os.getenv("GEMINI_OCR_MODEL", DEFAULT_MODEL)
    logger.info("OCR CCCD dùng Gemini (%s), EasyOCR làm dự phòng.", model)
    return GeminiOcrExtractor(genai.Client(api_key=api_key), model)


class GeminiOcrExtractor:
    """Trích thông tin CCCD qua Gemini vision, trả dict cùng khuôn OcrExtractor."""

    def __init__(self, client, model: str = DEFAULT_MODEL):
        self._client = client
        self._model = model

    def extract(self, image_bytes: bytes) -> dict:
        """Gọi Gemini đọc ảnh. Lỗi vận chuyển/quota nổ ra ngoài để bên gọi fallback."""
        response = self._client.models.generate_content(
            model=self._model,
            contents=[
                genai_types.Part.from_bytes(data=image_bytes, mime_type="image/jpeg"),
                PROMPT,
            ],
            config=genai_types.GenerateContentConfig(
                temperature=0,
                response_mime_type="application/json",
                response_schema=OcrFields,
                # Tắt thinking cho nhanh — bài đọc phiếu không cần suy luận dài.
                thinking_config=genai_types.ThinkingConfig(thinking_budget=0),
            ),
        )

        fields = OcrFields.model_validate(json.loads(response.text))
        result = self._normalize(fields)

        logger.info(
            "OCR CCCD (gemini): id=%s, name=%s, dob=%s, gender=%s, origin=%s, address=%s, conf=%.2f",
            result["id_number"] is not None, result["full_name"] is not None,
            result["date_of_birth"] is not None, result["gender"] is not None,
            result["place_of_origin"] is not None, result["address"] is not None,
            result["confidence"],
        )
        return result

    @staticmethod
    def _normalize(fields: OcrFields) -> dict:
        """Ép dữ liệu model trả về đúng khuôn backend chờ; giá trị lạ coi như thiếu."""
        id_number = re.sub(r"\s", "", fields.id_number or "")
        if not RE_ID_NUMBER.fullmatch(id_number):
            id_number = ""

        date_of_birth = None
        if fields.date_of_birth:
            match = RE_DATE.fullmatch(fields.date_of_birth.strip())
            if match:
                day, month, year = match.groups()
                date_of_birth = f"{int(day):02d}/{int(month):02d}/{year}"

        gender = fields.gender.strip() if fields.gender else None
        if gender not in ("Nam", "Nữ"):
            gender = None

        return {
            "success": bool(id_number),
            "id_number": id_number or None,
            "full_name": (fields.full_name or "").strip().upper() or None,
            "date_of_birth": date_of_birth,
            "gender": gender,
            "place_of_origin": (fields.place_of_origin or "").strip() or None,
            "address": (fields.address or "").strip() or None,
            "confidence": round(min(max(fields.confidence, 0.0), 1.0), 4),
        }
