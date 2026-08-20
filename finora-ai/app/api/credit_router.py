"""
Router cho API Chấm điểm Tín dụng (Credit Scoring).

Luồng ra quyết định:

    Hồ sơ vay (app/Fineract) + CCCD
        ├──→ cic-service (HTTP, ?chiTiet=true)
        │       ──→ cic_data dict (diemCic + 9 trường thô) hoặc None
        │
        ├──────────────────────────┬──────────────────────────┐
        ▼                          ▼                          │
    Mô hình XGBoost v14          Rule Engine 5C                │
    (47 features, có CIC)→ PD    (4 yếu tố) → risk_score      │
        └──────────────────────────┴──────────────────────────┘
                                  ▼
            evaluation_score = (1-PD)x100 x 0,6 + risk_score x 0,4
                                  ▼
                    credit_grade · decision · hạn mức

TODO: /explain (SHAP), /backtest.
"""
from functools import lru_cache

from fastapi import APIRouter, HTTPException, status

from app.ml.credit.predictor import BoDuDoan
from app.schemas.credit import CreditScoreRequest, CreditScoreResponse
from app.services.credit.cic_client import CicClient

router = APIRouter()


@lru_cache(maxsize=1)
def lay_bo_du_doan() -> BoDuDoan:
    """Nạp gói model một lần rồi dùng lại.

    Gói nặng ~1,9MB; nạp lại mỗi request sẽ tốn vô ích. `lru_cache` giữ instance
    trong suốt vòng đời tiến trình. Muốn nạp lại sau khi huấn luyện mô hình mới:
    khởi động lại service, hoặc gọi `lay_bo_du_doan.cache_clear()`.
    """
    return BoDuDoan.nap()


@lru_cache(maxsize=1)
def lay_cic_client() -> CicClient:
    """CicClient singleton — cấu hình qua env CIC_SERVICE_URL."""
    return CicClient()


@router.post("/score", response_model=CreditScoreResponse)
async def score_credit(ho_so: CreditScoreRequest) -> CreditScoreResponse:
    """Chấm điểm tín dụng cho một hồ sơ vay."""
    try:
        bo_du_doan = lay_bo_du_doan()
    except (FileNotFoundError, ValueError) as loi:
        # Gói model thiếu, hỏng, hoặc lệch bộ đặc trưng so với code hiện tại.
        # Trả 503 thay vì 500: đây là vấn đề cấu hình triển khai, không phải lỗi
        # của request — và tuyệt đối không được chấm điểm bằng gói không tin cậy.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "MODEL_NOT_AVAILABLE",
                "message": "Không nạp được gói model chấm điểm.",
                "details": [str(loi)],
            },
        ) from loi

    # Tra dữ liệu CIC nếu có CCCD
    cic_data: dict | None = None
    if ho_so.so_cccd:
        cic_client = lay_cic_client()
        cic_data = await cic_client.tra_diem_cic(ho_so.so_cccd)

    ket_qua = bo_du_doan.du_doan(
        ho_so.model_dump(exclude_none=True),
        cic_data=cic_data,
    )
    return CreditScoreResponse(**ket_qua)
