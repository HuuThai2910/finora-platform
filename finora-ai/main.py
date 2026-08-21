"""
FINORA AI Service — Entry Point
Chạy bằng: uvicorn main:app --reload --port 8000
"""
import logging
import os
from pathlib import Path


def _load_dotenv() -> None:
    """Nạp `.env` cạnh main.py vào môi trường (KEY=VALUE, # là comment).

    Tự viết vài dòng thay vì thêm dependency python-dotenv. Biến đã có sẵn
    trong môi trường được giữ nguyên để còn override từ ngoài khi cần.
    """
    env_file = Path(__file__).parent / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


_load_dotenv()

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import credit_router, ekyc_router, fraud_router
from app.api import config_router

# Uvicorn chỉ cấu hình logger của chính nó; logger của app không có handler nên
# log INFO (như dòng chẩn đoán OCR) bị nuốt. Bật handler gốc để nhìn thấy chúng.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

app = FastAPI(
    title="FINORA AI Service",
    description="Dịch vụ Trí tuệ Nhân tạo cho hệ thống P2P Lending FINORA",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# === Register Routers ===
app.include_router(credit_router.router, prefix="/api/v1/ai/credit", tags=["Credit Scoring"])
app.include_router(config_router.router, prefix="/api/v1/ai/config", tags=["Product Config"])
app.include_router(ekyc_router.router, prefix="/api/v1/ai/ekyc", tags=["eKYC"])
app.include_router(fraud_router.router, prefix="/api/v1/ai/fraud", tags=["Fraud Detection"])


@app.get("/health")
async def health_check():
    return {"status": "FINORA AI Service is running!"}
