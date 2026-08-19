"""Pydantic schemas cho Document Forgery Detection."""

from pydantic import BaseModel, Field


class DocumentVerifyRequest(BaseModel):
    """Yêu cầu kiểm tra giả mạo tài liệu."""

    image_base64: str = Field(
        ...,
        description="Ảnh tài liệu mã hoá base64 (JPEG/PNG)",
    )


class ElaDetail(BaseModel):
    """Chi tiết kết quả Error Level Analysis."""

    is_tampered: bool = False
    max_error: float = 0.0
    mean_error: float = 0.0
    suspicious_area_pct: float = Field(
        0.0,
        description="Phần trăm diện tích bất thường",
    )


class MetadataDetail(BaseModel):
    """Chi tiết kết quả kiểm tra EXIF metadata."""

    flags: list[str] = Field(
        default_factory=list,
        description="Các cờ cảnh báo: MISSING_EXIF, EDITED_BY_SOFTWARE, NO_CAMERA_INFO",
    )
    details: dict[str, str] = Field(
        default_factory=dict,
        description="EXIF tags đã trích xuất",
    )


class DocumentVerifyResponse(BaseModel):
    """Kết quả kiểm tra giả mạo tài liệu."""

    is_tampered: bool = Field(
        ...,
        description="Tài liệu có dấu hiệu giả mạo",
    )
    confidence: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Độ tin cậy (0-1)",
    )
    ela: ElaDetail = Field(
        ...,
        description="Kết quả Error Level Analysis",
    )
    metadata: MetadataDetail = Field(
        ...,
        description="Kết quả kiểm tra EXIF metadata",
    )
