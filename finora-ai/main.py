"""
FINORA AI Service — Entry Point
Chạy bằng: uvicorn main:app --reload --port 8000
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import credit_router, ekyc_router, fraud_router
from app.api import config_router

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
app.include_router(ekyc_router.router, prefix="/api/v1/ai/ekyc", tags=["eKYC & Face Match"])
app.include_router(fraud_router.router, prefix="/api/v1/ai/fraud", tags=["Fraud Detection"])


@app.get("/health")
async def health_check():
    return {"status": "FINORA AI Service is running!"}
