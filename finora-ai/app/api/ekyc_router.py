"""Router eKYC — OCR CCCD, face matching, liveness detection."""

import logging
from functools import lru_cache

from fastapi import APIRouter

from app.schemas.ekyc import (
    OcrRequest, OcrResponse,
    FaceMatchRequest, FaceMatchResponse,
    LivenessRequest, LivenessResponse,
)
from app.services.ekyc_service import EkycService

logger = logging.getLogger(__name__)
router = APIRouter()


@lru_cache(maxsize=1)
def get_ekyc_service() -> EkycService:
    """Singleton eKYC service."""
    logger.info("Khởi tạo EkycService...")
    return EkycService()


@router.post("/ocr", response_model=OcrResponse)
async def ocr_cccd(request: OcrRequest):
    """Trích xuất thông tin từ ảnh CCCD bằng OCR.

    Luồng: Ảnh base64 → EasyOCR → regex extract → thông tin CCCD
    """
    service = get_ekyc_service()
    result = service.ocr(request.image_base64)
    return OcrResponse(**result)


@router.post("/face-match", response_model=FaceMatchResponse)
async def face_match(request: FaceMatchRequest):
    """So khớp khuôn mặt selfie với ảnh trên CCCD.

    Luồng: 2 ảnh base64 → DeepFace verify → similarity score → match/no match
    """
    service = get_ekyc_service()
    result = service.face_match(request.selfie_base64, request.cccd_image_base64)
    return FaceMatchResponse(**result)


@router.post("/liveness", response_model=LivenessResponse)
async def check_liveness(request: LivenessRequest):
    """Kiểm tra ảnh có phải chụp trực tiếp (live) hay từ màn hình/ảnh in.

    Luồng: Ảnh base64 → LBP texture analysis → variance check → live/fake
    """
    service = get_ekyc_service()
    result = service.liveness(request.image_base64)
    return LivenessResponse(**result)
