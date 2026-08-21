"""Pydantic schemas cho eKYC endpoints (OCR, Face Match, Liveness)."""

from pydantic import BaseModel, Field

from app.ml.ekyc.active_liveness import SUPPORTED_ACTIONS
from app.ml.ekyc.thresholds import MAX_FRAMES


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
    """Yêu cầu kiểm tra liveness thụ động trên một ảnh."""

    image_base64: str = Field(..., min_length=1, description="Ảnh/frame base64")


class LivenessResponse(BaseModel):
    """Kết quả kiểm tra liveness thụ động."""

    is_live: bool
    confidence: float = Field(ge=0.0, le=1.0)
    method: str = Field(description="Phương pháp kiểm tra: lbp_texture | frequency")


class ActiveLivenessRequest(BaseModel):
    """Yêu cầu kiểm tra active liveness theo chuỗi hành động của phiên challenge."""

    frames: list[str] = Field(
        ...,
        min_length=1,
        max_length=MAX_FRAMES,
        description="Các frame base64, xếp theo đúng thứ tự thời gian",
    )
    expected_actions: list[str] = Field(
        ...,
        min_length=1,
        max_length=len(SUPPORTED_ACTIONS),
        description=f"Chuỗi hành động cần thực hiện, thuộc {list(SUPPORTED_ACTIONS)}",
    )


class ActionCheck(BaseModel):
    """Kết quả kiểm tra một hành động trong chuỗi."""

    action: str
    passed: bool
    evidence: str = Field(description="Bằng chứng/lý do — dùng để hiển thị và lưu vết")


class ActiveLivenessResponse(BaseModel):
    """Kết quả active liveness.

    ``best_frame_index`` là frame nét nhất có khuôn mặt chính diện — bên gọi
    dùng đúng frame này cho bước so khớp khuôn mặt.
    """

    is_live: bool
    actions: list[ActionCheck]
    confidence: float = Field(ge=0.0, le=1.0)
    method: str
    best_frame_index: int | None = None
    passive_check: LivenessResponse | None = Field(
        default=None,
        description="Kết quả lớp phụ LBP chạy trên vùng mặt của frame tốt nhất",
    )
