"""
Router cho API Chấm điểm Tín dụng (Credit Scoring).

Luồng ra quyết định gồm hai nhánh chạy song song rồi hợp lại:

    Hồ sơ vay
        ├──────────────────────────┬──────────────────────────┐
        ▼                          ▼                          │
    Mô hình XGBoost             Rule Engine 5C                │
    (21 đặc trưng) → PD         (4 yếu tố) → risk_score       │
        └──────────────────────────┴──────────────────────────┘
                                  ▼
            evaluation_score = (1-PD)x100 x 0,6 + risk_score x 0,4
                                  ▼
                    credit_grade · decision · hạn mức · lãi suất

Không có tầng cổng chặn CIC: FINORA chưa có kết nối API tới Trung tâm Thông tin Tín
dụng nên không lấy được nhóm nợ hay điểm tín dụng để loại hồ sơ nợ xấu. Việc chặn
theo điều kiện pháp lý phải nằm ở tầng nghiệp vụ khác, không phải ở AI Service.

TODO: /explain (SHAP), /backtest.
"""
from functools import lru_cache

from fastapi import APIRouter, HTTPException, status

from app.ml.predictor import BoDuDoan
from app.schemas.credit import CreditScoreRequest, CreditScoreResponse

router = APIRouter()


@lru_cache(maxsize=1)
def lay_bo_du_doan() -> BoDuDoan:
    """Nạp gói model một lần rồi dùng lại.

    Gói nặng ~1,9MB; nạp lại mỗi request sẽ tốn vô ích. `lru_cache` giữ instance
    trong suốt vòng đời tiến trình. Muốn nạp lại sau khi huấn luyện mô hình mới:
    khởi động lại service, hoặc gọi `lay_bo_du_doan.cache_clear()`.
    """
    return BoDuDoan.nap()


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

    ket_qua = bo_du_doan.du_doan(ho_so.model_dump(exclude_none=True))
    return CreditScoreResponse(**ket_qua)
