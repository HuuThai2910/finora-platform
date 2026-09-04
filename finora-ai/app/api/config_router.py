"""
Router cho API Cấu hình sản phẩm.

Hai nhóm endpoint:

  · /product — khoảng điểm AI, hạng tín dụng, hạn mức, ngưỡng duyệt, trọng số.
               Đây là cấu hình QUANH rule engine: quyết định điểm nào ra hạng nào.
  · /rules   — ngưỡng và điểm của từng luật chấm điểm, bật/tắt luật.
               Đây là cấu hình CỦA rule engine: quyết định hồ sơ được bao nhiêu điểm.

Thay đổi ghi xuống config/product_config.json và có hiệu lực ngay, không cần
khởi động lại service.

Vì sao ràng buộc chặt ở /rules
------------------------------
Ngưỡng hiện tại được chọn từ 150.000 hồ sơ thật và đạt AUC 0,6356
(scripts/validate_rule_engine.py). Admin kéo bừa thì AUC tụt mà không có gì báo —
mô hình vẫn trả về số, chỉ là số sai. Nên API chặn trước những sai lầm khiến bộ
luật mất ý nghĩa: bậc điểm không đơn điệu, ngưỡng không đúng thứ tự, tắt hết luật.
"""
from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field, field_validator

from app.services.credit.product_config import reload, save
from app.services.credit.rule_engine import (
    _DINH_NGHIA_LUAT,
    DIEM_TOI_DA_MOI_LUAT,
    MA_LUAT_HOP_LE,
    TINH_TRANG_NHA_O,
)

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
    max_total_debt_all_platforms: int
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

    config = save(current)
    return ProductConfigResponse(**config)


# ── Cấu hình luật chấm điểm ───────────────────────────────────────────────────

# Ngưỡng vô cực trong JSON. JSON không có Infinity, nên bậc cuối dùng số này để
# biểu diễn "mọi giá trị còn lại".
NGUONG_VO_CUC = 1e9

# `DIEM_TOI_DA_MOI_LUAT` nhập từ rule_engine, không khai báo lại: validator ở đây
# bắt bậc tốt nhất của mỗi luật phải bằng đúng trần đó, còn `cham_diem_chi_tiet`
# quy đổi điểm theo chính trần đó. Hai bản sao lệch nhau nghĩa là API từ chối một
# cấu hình mà engine chấm được, hoặc ngược lại.


class RuleConfig(BaseModel):
    """Phần cấu hình được của một luật."""

    bat: bool = Field(default=True, description="Tắt thì luật không tham gia chấm điểm")
    diem_khi_thieu: int = Field(
        ge=0, le=DIEM_TOI_DA_MOI_LUAT,
        description="Điểm trung tính khi hồ sơ không có dữ liệu cho luật này",
    )
    bac: list[tuple[float, int]] | None = Field(
        default=None, description="Các bậc (ngưỡng, điểm). Dùng cho luật so ngưỡng."
    )
    bang_diem: dict[str, int] | None = Field(
        default=None, description="Bảng điểm theo giá trị rời rạc. Dùng cho luật nhà ở."
    )

    @field_validator("bac")
    @classmethod
    def _kiem_tra_bac(cls, bac):
        if bac is None:
            return bac
        if len(bac) < 2:
            raise ValueError("mỗi luật phải có ít nhất 2 bậc điểm")
        for _, diem in bac:
            if not 0 <= diem <= DIEM_TOI_DA_MOI_LUAT:
                raise ValueError(f"điểm mỗi bậc phải trong khoảng 0-{DIEM_TOI_DA_MOI_LUAT}")
        diem_cac_bac = [diem for _, diem in bac]
        if diem_cac_bac != sorted(diem_cac_bac, reverse=True):
            raise ValueError(
                "điểm phải giảm dần theo thứ tự bậc — bậc đầu là tốt nhất, bậc cuối là xấu nhất"
            )
        if max(diem_cac_bac) != DIEM_TOI_DA_MOI_LUAT:
            raise ValueError(
                f"bậc tốt nhất phải đạt đúng {DIEM_TOI_DA_MOI_LUAT} điểm để các luật cân nhau"
            )
        return bac

    @field_validator("bang_diem")
    @classmethod
    def _kiem_tra_bang_diem(cls, bang):
        if bang is None:
            return bang
        if set(bang) != set(TINH_TRANG_NHA_O):
            raise ValueError(f"bảng điểm phải có đúng các khóa {list(TINH_TRANG_NHA_O)}")
        for diem in bang.values():
            if not 0 <= diem <= DIEM_TOI_DA_MOI_LUAT:
                raise ValueError(f"điểm phải trong khoảng 0-{DIEM_TOI_DA_MOI_LUAT}")
        if max(bang.values()) != DIEM_TOI_DA_MOI_LUAT:
            raise ValueError(
                f"loại tốt nhất phải đạt đúng {DIEM_TOI_DA_MOI_LUAT} điểm để các luật cân nhau"
            )
        return bang


class RuleInfo(RuleConfig):
    """Luật kèm phần mô tả cố định — dùng cho GET, frontend không phải tự dịch mã luật."""

    ma: str
    nhom_5c: str
    mo_ta: str
    nghich_dao: bool = Field(description="True nghĩa là giá trị càng thấp càng tốt")
    la_bang_diem: bool = Field(description="True thì luật tra bảng thay vì so ngưỡng")


class RulesResponse(BaseModel):
    rules: list[RuleInfo]
    diem_toi_da_moi_luat: int = DIEM_TOI_DA_MOI_LUAT
    nguong_vo_cuc: float = NGUONG_VO_CUC


class RulesUpdate(BaseModel):
    rules: dict[str, RuleConfig]


def _dung_rules_response() -> RulesResponse:
    cau_hinh = reload()["rules"]
    return RulesResponse(
        rules=[
            RuleInfo(
                ma=dn.ma,
                nhom_5c=dn.nhom_5c,
                mo_ta=dn.mo_ta,
                nghich_dao=dn.nghich_dao,
                la_bang_diem=dn.la_bang_diem,
                **cau_hinh[dn.ma],
            )
            for dn in _DINH_NGHIA_LUAT
        ]
    )


@router.get("/rules", response_model=RulesResponse)
async def get_rules_config():
    """Đọc cấu hình các luật chấm điểm kèm mô tả."""
    return _dung_rules_response()


@router.put("/rules", response_model=RulesResponse)
async def update_rules_config(body: RulesUpdate):
    """Cập nhật ngưỡng, điểm và trạng thái bật/tắt của các luật chấm điểm."""
    thieu = set(MA_LUAT_HOP_LE) - set(body.rules)
    la = set(body.rules) - set(MA_LUAT_HOP_LE)
    if thieu or la:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"phải gửi đúng {len(MA_LUAT_HOP_LE)} luật. "
                f"Thiếu: {sorted(thieu) or 'không'}. Không hợp lệ: {sorted(la) or 'không'}"
            ),
        )

    if not any(r.bat for r in body.rules.values()):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="phải bật ít nhất một luật, nếu không hệ thống không còn cơ sở chấm điểm",
        )

    # Luật tra bảng cần bang_diem, luật so ngưỡng cần bac — không được lẫn lộn.
    for dn in _DINH_NGHIA_LUAT:
        r = body.rules[dn.ma]
        can = "bang_diem" if dn.la_bang_diem else "bac"
        thua = "bac" if dn.la_bang_diem else "bang_diem"
        if getattr(r, can) is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"luật {dn.ma} phải có '{can}'",
            )
        if getattr(r, thua) is not None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"luật {dn.ma} không dùng '{thua}'",
            )
        if not dn.la_bang_diem:
            nguong = [n for n, _ in r.bac]
            dung_thu_tu = (
                nguong == sorted(nguong) if dn.nghich_dao else nguong == sorted(nguong, reverse=True)
            )
            if not dung_thu_tu:
                chieu = "tăng dần" if dn.nghich_dao else "giảm dần"
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail=f"luật {dn.ma}: ngưỡng phải {chieu} để bậc đầu là bậc tốt nhất",
                )

    current = reload()
    current["rules"] = {
        ma: r.model_dump(exclude_none=True) for ma, r in body.rules.items()
    }
    save(current)
    return _dung_rules_response()
