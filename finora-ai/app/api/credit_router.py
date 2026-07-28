"""
Router cho API Chấm điểm Tín dụng (Credit Scoring).
TODO: Implement endpoint /score, /explain (SHAP), /backtest.
"""
from fastapi import APIRouter

router = APIRouter()


@router.post("/score")
async def score_credit():
    """Chấm điểm tín dụng cho một hồ sơ vay."""
    return {"message": "TODO: Implement credit scoring"}
