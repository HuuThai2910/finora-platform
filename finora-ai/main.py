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

# Uvicorn chỉ cấu hình logger của chính nó; logger của app không có handler nên
# log INFO (như dòng chẩn đoán eKYC) bị nuốt. Bật handler gốc **trước** khi import
# router để cảnh báo lúc khởi động (ví dụ thiếu AI_INTERNAL_TOKEN) cũng có định dạng.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

# Windows: phải nạp zxing-cpp TRƯỚC scikit-learn (credit/fraud). scikit-learn 1.4 mang
# msvcp140.dll 14.32 trong sklearn/.libs; nạp trước thì zxing-cpp (biên dịch bằng MSVC
# mới hơn) dùng nhầm bản cũ và có thể crash cả tiến trình (access violation). Nạp
# zxing-cpp trước thì cả hai dùng bản hệ thống mới hơn. Linux/Docker không bị.
import zxingcpp  # noqa: F401
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import config_router, credit_router, ekyc_router, fraud_router

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
