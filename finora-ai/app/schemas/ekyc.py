"""Pydantic schemas cho eKYC endpoints (OCR, Face Match, Liveness)."""

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
    confidence: float = Field(ge=0.0, le=1.0)


class FaceMatchRequest(BaseModel):
    """Yêu cầu so khớp khuôn mặt selfie với ảnh CCCD."""

    selfie_base64: str = Field(..., min_length=1, description="Ảnh selfie base64")
    cccd_image_base64: str = Field(..., min_length=1, description="Ảnh CCCD base64")


class FaceMatchResponse(BaseModel):
    """Kết quả so khớp khuôn mặt."""

    match: bool
    similarity: float = Field(ge=0.0, le=1.0)
    threshold: float = Field(ge=0.0, le=1.0)


class LivenessRequest(BaseModel):
    """Yêu cầu kiểm tra liveness."""

    image_base64: str = Field(..., min_length=1, description="Ảnh/frame base64")


class LivenessResponse(BaseModel):
    """Kết quả kiểm tra liveness."""

    is_live: bool
    confidence: float = Field(ge=0.0, le=1.0)
    method: str = Field(description="Phương pháp kiểm tra: lbp_texture | frequency")
