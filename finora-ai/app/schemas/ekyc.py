"""Pydantic schemas cho eKYC endpoints (OCR CCCD)."""

from pydantic import BaseModel, Field


class OcrRequest(BaseModel):
    """Yêu cầu OCR trích xuất thông tin từ ảnh CCCD."""

    image_base64: str = Field(..., min_length=1, description="Ảnh CCCD mã hoá base64")


class OcrResponse(BaseModel):
    """Kết quả OCR từ ảnh CCCD."""

    success: bool
    id_number: str | None = None
    full_name: str | None = None
    date_of_birth: str | None = None
    gender: str | None = None
    place_of_origin: str | None = None
    address: str | None = Field(default=None, description="Nơi thường trú in trên mặt trước")
    confidence: float = Field(ge=0.0, le=1.0)
