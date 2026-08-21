"""Router eKYC — OCR CCCD (luồng định danh bằng ảnh giấy tờ hai mặt)."""

import logging
from functools import lru_cache

from fastapi import APIRouter

from app.schemas.ekyc import OcrRequest, OcrResponse
from app.services.ekyc.service import EkycService

logger = logging.getLogger(__name__)
router = APIRouter()


@lru_cache(maxsize=1)
def get_ekyc_service() -> EkycService:
    """Singleton eKYC service."""
    logger.info("Khởi tạo EkycService...")
    return EkycService()


@router.post("/ocr", response_model=OcrResponse)
async def ocr_cccd(request: OcrRequest):
    """Trích xuất thông tin từ ảnh CCCD.

    Engine chính là Gemini vision (khi cấu hình ``GEMINI_API_KEY``); EasyOCR
    là dự phòng offline.
    """
    service = get_ekyc_service()
    result = service.ocr(request.image_base64)
    return OcrResponse(**result)
