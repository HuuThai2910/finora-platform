"""
Router cho API eKYC & Face Match.
TODO: Implement endpoint /verify-face, /read-nfc.
"""
import base64
import logging

from fastapi import APIRouter, HTTPException, status

from app.ml.document_analysis import combined_verdict
from app.schemas.document import (
    DocumentVerifyRequest,
    DocumentVerifyResponse,
    ElaDetail,
    MetadataDetail,
)

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/verify-face")
async def verify_face():
    """So khớp khuôn mặt selfie với ảnh trên CCCD."""
    return {"message": "TODO: Implement face matching"}


@router.post("/verify-document", response_model=DocumentVerifyResponse)
async def verify_document(req: DocumentVerifyRequest) -> DocumentVerifyResponse:
    """Phân tích tài liệu — phát hiện chỉnh sửa bằng ELA + EXIF metadata."""
    try:
        image_bytes = base64.b64decode(req.image_base64)
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"base64 không hợp lệ: {e}",
        ) from e

    if len(image_bytes) < 100:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Ảnh quá nhỏ hoặc không hợp lệ.",
        )

    result = combined_verdict(image_bytes)

    return DocumentVerifyResponse(
        is_tampered=result["is_tampered"],
        confidence=result["confidence"],
        ela=ElaDetail(**result["ela"]),
        metadata=MetadataDetail(**result["metadata"]),
    )
