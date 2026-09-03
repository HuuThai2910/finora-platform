"""
Router cho API Chấm điểm Tín dụng (Credit Scoring).

Luồng ra quyết định:

    Hồ sơ vay (app/Fineract) + CCCD
        ├──→ cic-service (HTTP, ?chiTiet=true)
        │       ──→ cic_data dict (diemCic + 9 trường thô) hoặc None
        │
        ├──────────────────────────┬──────────────────────────┐
        ▼                          ▼                          │
    Mô hình XGBoost              Rule Engine 5C                │
    (47 features, có CIC)→ PD    (4 yếu tố) → risk_score      │
        └──────────────────────────┴──────────────────────────┘
                                  ▼
       evaluation_score = (1-PD)x100 x pd_weight + risk_score x risk_weight
              (trọng số đọc từ config/product_config.json)
                                  ▼
                    credit_grade · decision · hạn mức

`/explain` giải thích cùng một quyết định đó bằng TreeSHAP (nửa ML) cộng với
rule trace 5C (nửa quy tắc) — xem `app/ml/credit/explainer.py`.

TODO: /backtest.
"""
from functools import lru_cache

from fastapi import APIRouter, HTTPException, status

from app.ml.credit.dien_giai import sinh_dien_giai
from app.ml.credit.explainer import giai_thich_mo_hinh
from app.ml.credit.predictor import BoDuDoan
from app.schemas.credit import (
    CreditExplainResponse,
    CreditScoreRequest,
    CreditScoreResponse,
)
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


def _nap_bo_du_doan_hoac_503() -> BoDuDoan:
    """Nạp gói model, đổi lỗi nạp gói thành 503.

    Dùng chung cho `/score` và `/explain`: cả hai đều phải từ chối phục vụ khi gói
    model không tin cậy, và phải từ chối theo cùng một cách.
    """
    try:
        return lay_bo_du_doan()
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


async def _tra_cic(ho_so: CreditScoreRequest) -> dict | None:
    """Tra dữ liệu CIC nếu hồ sơ có CCCD, None khi không có hoặc cic-service lỗi."""
    if not ho_so.so_cccd:
        return None
    return await lay_cic_client().tra_diem_cic(ho_so.so_cccd)


@router.post("/score", response_model=CreditScoreResponse)
async def score_credit(ho_so: CreditScoreRequest) -> CreditScoreResponse:
    """Chấm điểm tín dụng cho một hồ sơ vay."""
    bo_du_doan = _nap_bo_du_doan_hoac_503()
    cic_data = await _tra_cic(ho_so)

    ket_qua = bo_du_doan.du_doan(
        ho_so.model_dump(exclude_none=True),
        cic_data=cic_data,
    )
    return CreditScoreResponse(**ket_qua)


@router.post("/explain", response_model=CreditExplainResponse)
async def explain_credit(ho_so: CreditScoreRequest) -> CreditExplainResponse:
    """Giải thích quyết định chấm điểm của một hồ sơ vay (C1.2).

    Nhận đúng schema request của `/score` để người gọi giải thích được chính hồ sơ
    vừa chấm mà không phải dựng lại dữ liệu. Endpoint tự chấm lại thay vì nhận
    `pd_probability` từ client: mô hình là tất định, chấm lại rẻ, và nhận điểm từ
    bên ngoài sẽ cho phép giải thích một con số mà mô hình chưa từng sinh ra.
    """
    bo_du_doan = _nap_bo_du_doan_hoac_503()
    cic_data = await _tra_cic(ho_so)

    du_lieu = ho_so.model_dump(exclude_none=True)
    if cic_data is not None:
        du_lieu = {**du_lieu, **cic_data}

    # Chấm lại bằng đúng đường của `/score` để giải thích không bao giờ mô tả một
    # quyết định khác với quyết định thật.
    ket_qua = bo_du_doan.du_doan(du_lieu)

    return CreditExplainResponse(
        pd_probability=ket_qua["pd_probability"],
        risk_score=ket_qua["risk_score"],
        evaluation_score=ket_qua["evaluation_score"],
        credit_grade=ket_qua["credit_grade"],
        decision=ket_qua["decision"],
        dien_giai=sinh_dien_giai(ket_qua),
        giai_thich_mo_hinh=giai_thich_mo_hinh(bo_du_doan, du_lieu),
        rule_trace=ket_qua["rule_trace"],
        rejection_reasons=ket_qua["rejection_reasons"],
        model_version=ket_qua["model_version"],
    )
