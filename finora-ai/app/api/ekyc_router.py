"""
Router cho API eKYC & Face Match.
TODO: Implement endpoint /verify-face, /verify-document, /read-nfc.
"""
from fastapi import APIRouter

router = APIRouter()


@router.post("/verify-face")
async def verify_face():
    """So khớp khuôn mặt selfie với ảnh trên CCCD."""
    return {"message": "TODO: Implement face matching"}
