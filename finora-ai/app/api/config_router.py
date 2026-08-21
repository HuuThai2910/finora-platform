"""
Router cho API Cấu hình sản phẩm.

Cho phép frontend đọc và cập nhật khoảng điểm AI, hạng tín dụng, hạn mức
và ngưỡng duyệt tự động. Thay đổi được ghi xuống config/product_config.json
và có hiệu lực ngay lập tức mà không cần khởi động lại service.
"""
import json

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field

from app.services.credit.product_config import _CONFIG_PATH, reload

router = APIRouter()


class GradeConfig(BaseModel):
    grade: str
    min_score: int = Field(ge=0, le=100)
    max_score: int = Field(ge=0, le=100)
    limit: int = Field(ge=0)


class ApprovalThresholds(BaseModel):
    auto_approve: int = Field(ge=0, le=100)
    auto_reject: int = Field(ge=0, le=100)


class ModelWeights(BaseModel):
    pd_weight: float = Field(ge=0, le=1)
    risk_weight: float = Field(ge=0, le=1)


class LegalLimits(BaseModel):
    max_platform_limit: int
    max_interest_rate: float
    max_term_months: int


class ProductConfigResponse(BaseModel):
    grades: list[GradeConfig]
    approval_thresholds: ApprovalThresholds
    model_weights: ModelWeights
    legal_limits: LegalLimits


class ProductConfigUpdate(BaseModel):
    grades: list[GradeConfig]
    approval_thresholds: ApprovalThresholds
    model_weights: ModelWeights | None = None


@router.get("/product", response_model=ProductConfigResponse)
async def get_product_config():
    """Đọc cấu hình sản phẩm hiện tại."""
    config = reload()
    return ProductConfigResponse(**config)


@router.put("/product", response_model=ProductConfigResponse)
async def update_product_config(body: ProductConfigUpdate):
    """Cập nhật khoảng điểm AI, ngưỡng duyệt và trọng số. Legal limits không đổi."""
    if body.approval_thresholds.auto_reject >= body.approval_thresholds.auto_approve:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="auto_reject phải nhỏ hơn auto_approve",
        )

    if body.model_weights is not None:
        total = round(body.model_weights.pd_weight + body.model_weights.risk_weight, 4)
        if total != 1.0:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"pd_weight + risk_weight phải bằng 1.0, hiện tại = {total}",
            )

    current = reload()
    current["grades"] = [g.model_dump() for g in body.grades]
    current["approval_thresholds"] = body.approval_thresholds.model_dump()
    if body.model_weights is not None:
        current["model_weights"] = body.model_weights.model_dump()

    _CONFIG_PATH.write_text(
        json.dumps(current, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    config = reload()
    return ProductConfigResponse(**config)
