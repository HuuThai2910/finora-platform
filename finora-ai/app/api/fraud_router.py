"""
Router cho API Fraud Detection.
TODO: Implement endpoint /detect (Isolation Forest), /check-document (ELA).
"""
from fastapi import APIRouter

router = APIRouter()


@router.post("/detect")
async def detect_fraud():
    """Phát hiện hành vi rửa tiền bất thường."""
    return {"message": "TODO: Implement fraud detection"}
