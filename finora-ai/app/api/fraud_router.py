"""
Router cho API Fraud Detection.

Luồng chấm rủi ro một giao dịch ví:

    Giao dịch ví (finora-payment)
        │   loại · số tiền · số dư gửi/nhận · giờ
        │   + lịch sử ví nhận (payment behavior contract)
        ▼
    Kiểm tra phạm vi mô hình  ──→ ngoài TRANSFER/CASH_OUT: trả về ngay,
        │                          da_cham_bang_mo_hinh = False
        ▼
    Gói model gian lận v1.0.0 (XGBoost, 14 đặc trưng hành vi)
        │
        ├──→ fraud_probability
        └──→ bằng chứng TreeSHAP (đặc trưng đẩy điểm lên phía gian lận)
        ▼
    finora-payment áp policy — chặn, giữ tiền hay yêu cầu xác thực bổ sung

`finora-ai` KHÔNG tự chặn giao dịch hay khóa ví: theo
`.agents/rules/07-service-boundaries.md`, service này sở hữu *fraud technical
result*, còn hành động thuộc Payment.

TODO: /check-document (phát hiện giấy tờ giả bằng ELA) — roadmap P7-B06.
"""

from functools import lru_cache

from fastapi import APIRouter, HTTPException, status

from app.ml.fraud.predictor import BoPhatHienGianLan
from app.schemas.fraud import FraudDetectRequest, FraudDetectResponse
from app.services.fraud.service import cham_giao_dich

router = APIRouter()


@lru_cache(maxsize=1)
def lay_bo_phat_hien_gian_lan() -> BoPhatHienGianLan:
    """Nạp gói model gian lận một lần rồi dùng lại.

    Cùng lý do với `lay_bo_du_doan()` bên credit_router: nạp lại mỗi request là
    lãng phí thuần túy. Muốn nạp gói mới sau khi huấn luyện: khởi động lại
    service, hoặc gọi `lay_bo_phat_hien_gian_lan.cache_clear()`.
    """
    return BoPhatHienGianLan.nap()


@router.post("/detect", response_model=FraudDetectResponse)
async def detect_fraud(giao_dich: FraudDetectRequest) -> FraudDetectResponse:
    """Chấm rủi ro gian lận cho một giao dịch ví."""
    try:
        bo_phat_hien = lay_bo_phat_hien_gian_lan()
    except (FileNotFoundError, ValueError) as loi:
        # Gói model thiếu, hỏng, hoặc lệch bộ đặc trưng so với code hiện tại.
        # Trả 503 thay vì 500 vì đây là vấn đề cấu hình triển khai chứ không phải
        # lỗi của request — và tuyệt đối không chấm bằng gói không tin cậy: chấm
        # sai ở đây nghĩa là thả lọt giao dịch xấu hoặc chặn nhầm khách hàng thật.
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail={
                "code": "MODEL_NOT_AVAILABLE",
                "message": "Không nạp được gói model phát hiện gian lận.",
                "details": [str(loi)],
            },
        ) from loi

    ket_qua = cham_giao_dich(bo_phat_hien, giao_dich.model_dump())
    return FraudDetectResponse(ma_giao_dich=giao_dich.ma_giao_dich, **ket_qua)
