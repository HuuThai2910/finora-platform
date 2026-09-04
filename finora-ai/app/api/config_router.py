"""
Router cho API Cấu hình sản phẩm.

Hai nhóm endpoint:

  · /product — khoảng điểm AI, hạng tín dụng, hạn mức, ngưỡng duyệt, trọng số.
               Đây là cấu hình QUANH rule engine: quyết định điểm nào ra hạng nào.
  · /rules   — toàn bộ bộ luật chấm điểm: admin thêm, sửa, xoá, sắp xếp, bật/tắt
               luật tuỳ ý. Đây là cấu hình CỦA rule engine: quyết định hồ sơ được
               bao nhiêu điểm.

Thay đổi ghi xuống config/product_config.json và có hiệu lực ngay, không cần
khởi động lại service.

Vì sao ràng buộc chặt ở /rules
------------------------------
Bộ luật mặc định được chọn từ 150.000 hồ sơ thật và đạt AUC 0,6356
(scripts/validate_rule_engine.py). Admin kéo bừa thì AUC tụt mà không có gì báo —
mô hình vẫn trả về số, chỉ là số sai. Nên API chặn trước những sai lầm khiến bộ
luật mất ý nghĩa: bậc điểm không đơn điệu, ngưỡng không đúng thứ tự, trường không
có trong danh mục, tắt hết luật. PUT thay TOÀN BỘ danh sách và nguyên tử: lỗi ở
bất kỳ luật nào thì không luật nào được ghi.
"""

import re
from itertools import pairwise

from fastapi import APIRouter, HTTPException, status
from pydantic import BaseModel, Field, field_validator

from app.services.credit.product_config import get_legal_limits, reload, save
from app.services.credit.rule_engine import DIEM_TOI_DA_MOI_LUAT
from app.services.credit.truong_du_lieu import DANH_MUC_TRUONG, mo_ta_danh_muc

router = APIRouter()


class GradeConfig(BaseModel):
    """Một hạng tín dụng.

    `grade` để mở, không phải Literal cố định: bảng hạng là cấu hình động — admin
    thêm/sửa/xoá hạng qua endpoint này mà không cần deploy lại. Ràng buộc dưới đây
    chỉ chặn tên rác; tập hạng nào là hợp lệ do chính config quyết định.
    """

    grade: str = Field(min_length=1, max_length=8, pattern=r"^[A-Z][A-Z0-9+-]*$")
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


def _kiem_tra_bang_hang(grades: list[GradeConfig]) -> None:
    """Bảng hạng phải phủ kín 0-100, không hở, không chồng, tên không trùng.

    Bảng hạng động thì lỗi cấu hình chuyển thành lỗi RUNTIME, nên phải chặn ngay
    lúc lưu. Không kiểm ở đây thì admin lưu thành công rồi mọi request chấm điểm
    rơi vào khoảng hở sẽ vỡ — mà lúc đó không còn manh mối nào chỉ về cái PUT này.
    """
    if not grades:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Phải có ít nhất một hạng.",
        )

    ten = [g.grade for g in grades]
    trung = {t for t in ten if ten.count(t) > 1}
    if trung:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Tên hạng bị trùng: {sorted(trung)}",
        )

    for g in grades:
        if g.min_score >= g.max_score:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Hạng {g.grade}: min_score ({g.min_score}) phải nhỏ hơn max_score ({g.max_score}).",
            )

    # Trần pháp lý kiểm ngay lúc lưu. Trước đây `_build_bang_xep_hang()` mới raise,
    # tức là lúc chấm điểm — API nhận 200 rồi mọi lần chấm sau đó trả 500.
    tran = get_legal_limits()["max_platform_limit"]
    for g in grades:
        if g.limit > tran:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"Hạng {g.grade}: hạn mức {g.limit:,} vượt trần "
                    f"{tran:,} đồng/khách hàng/nền tảng (Quyết định 2866/QĐ-NHNN)."
                ),
            )

    theo_diem = sorted(grades, key=lambda g: g.min_score)
    if theo_diem[0].min_score != 0:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Hạng thấp nhất ({theo_diem[0].grade}) phải bắt đầu từ 0 điểm.",
        )
    if theo_diem[-1].max_score != 100:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Hạng cao nhất ({theo_diem[-1].grade}) phải kết thúc ở 100 điểm.",
        )
    for duoi, tren in pairwise(theo_diem):
        if duoi.max_score != tren.min_score:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=(
                    f"Khoảng điểm hở hoặc chồng giữa {duoi.grade} (đến {duoi.max_score}) "
                    f"và {tren.grade} (từ {tren.min_score})."
                ),
            )


@router.put("/product", response_model=ProductConfigResponse)
async def update_product_config(body: ProductConfigUpdate):
    """Cập nhật khoảng điểm AI, ngưỡng duyệt và trọng số. Legal limits không đổi."""
    if body.approval_thresholds.auto_reject >= body.approval_thresholds.auto_approve:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="auto_reject phải nhỏ hơn auto_approve",
        )

    _kiem_tra_bang_hang(body.grades)

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

# Trọng số cho phép: 0,1 đến 10. Dưới 0,1 luật gần như vô nghĩa nhưng vẫn chiếm
# chỗ trong trace; trên 10 một luật át hết các luật khác — muốn thế thì tắt luật kia.
TRONG_SO_TOI_THIEU = 0.1
TRONG_SO_TOI_DA = 10.0

# Mã luật: chữ hoa, số, gạch dưới, bắt đầu bằng chữ, 3–64 ký tự. Mã xuất hiện trong
# rule_trace lưu kèm quyết định và trong log, nên phải ổn định và không có khoảng trắng.
MAU_MA_LUAT = r"^[A-Z][A-Z0-9_]{2,63}$"

# `DIEM_TOI_DA_MOI_LUAT` nhập từ rule_engine, không khai báo lại: validator ở đây
# bắt bậc tốt nhất của mỗi luật phải bằng đúng trần đó, còn `cham_diem_chi_tiet`
# quy đổi điểm theo chính trần đó. Hai bản sao lệch nhau nghĩa là API từ chối một
# cấu hình mà engine chấm được, hoặc ngược lại.


class RuleConfig(BaseModel):
    """Một luật chấm điểm đầy đủ — đây là đúng bản ghi được ghi vào config."""

    ma: str = Field(
        pattern=MAU_MA_LUAT, description="Mã luật, ổn định, không đổi sau khi tạo"
    )
    mo_ta: str = Field(
        min_length=1, max_length=200, description="Tên luật hiển thị cho người đọc"
    )
    truong: str = Field(
        description="Mã trường trong danh mục trường mà luật đọc giá trị"
    )
    nghich_dao: bool = Field(
        default=False,
        description="True nghĩa là giá trị càng thấp càng tốt (chỉ luật số)",
    )
    trong_so: float = Field(
        default=1.0,
        ge=TRONG_SO_TOI_THIEU,
        le=TRONG_SO_TOI_DA,
        description="Tỷ trọng so với các luật khác khi chuẩn hoá về thang 100",
    )
    bat: bool = Field(default=True, description="Tắt thì luật không tham gia chấm điểm")
    diem_khi_thieu: int = Field(
        ge=0,
        le=DIEM_TOI_DA_MOI_LUAT,
        description="Điểm trung tính khi hồ sơ không có dữ liệu cho luật này",
    )
    bac: list[tuple[float, int]] | None = Field(
        default=None, description="Các bậc (ngưỡng, điểm). Dùng cho trường kiểu số."
    )
    bang_diem: dict[str, int] | None = Field(
        default=None,
        description="Bảng điểm theo giá trị rời rạc. Dùng cho trường phân loại.",
    )
    goi_y: str | None = Field(
        default=None,
        max_length=300,
        description="Mẫu câu gợi ý cải thiện cho người vay, chỗ trống {moc} là ngưỡng kế tiếp",
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
                raise ValueError(
                    f"điểm mỗi bậc phải trong khoảng 0-{DIEM_TOI_DA_MOI_LUAT}"
                )
        diem_cac_bac = [diem for _, diem in bac]
        if diem_cac_bac != sorted(diem_cac_bac, reverse=True):
            raise ValueError(
                "điểm phải giảm dần theo thứ tự bậc — bậc đầu là tốt nhất, bậc cuối là xấu nhất"
            )
        if max(diem_cac_bac) != DIEM_TOI_DA_MOI_LUAT:
            raise ValueError(
                f"bậc tốt nhất phải đạt đúng {DIEM_TOI_DA_MOI_LUAT} điểm; "
                "muốn luật nặng nhẹ khác nhau thì dùng trong_so"
            )
        return bac

    @field_validator("bang_diem")
    @classmethod
    def _kiem_tra_bang_diem(cls, bang):
        if bang is None:
            return bang
        for diem in bang.values():
            if not 0 <= diem <= DIEM_TOI_DA_MOI_LUAT:
                raise ValueError(f"điểm phải trong khoảng 0-{DIEM_TOI_DA_MOI_LUAT}")
        if max(bang.values()) != DIEM_TOI_DA_MOI_LUAT:
            raise ValueError(
                f"loại tốt nhất phải đạt đúng {DIEM_TOI_DA_MOI_LUAT} điểm; "
                "muốn luật nặng nhẹ khác nhau thì dùng trong_so"
            )
        return bang

    @field_validator("goi_y")
    @classmethod
    def _kiem_tra_goi_y(cls, goi_y):
        """Chỉ cho phép chỗ trống `{moc}`. Chỗ trống lạ sẽ nổ KeyError lúc format
        — tức lúc người vay đang chờ kết quả, không phải lúc admin bấm lưu."""
        if goi_y is None or not goi_y.strip():
            return None
        cho_trong_la = set(re.findall(r"\{([^{}]*)\}", goi_y)) - {"moc"}
        if cho_trong_la:
            raise ValueError(
                f"mẫu gợi ý chỉ được dùng chỗ trống {{moc}}, không hiểu: {sorted(cho_trong_la)}"
            )
        return goi_y.strip()


class TruongInfo(BaseModel):
    """Một trường trong danh mục — để frontend dựng ô chọn trường và form bậc/bảng."""

    ma: str
    mo_ta: str
    kieu: str
    nguon: str
    don_vi: str
    la_ty_le: bool
    gia_tri_hop_le: list[str]
    nhom_shap: str | None


class RulesResponse(BaseModel):
    rules: list[RuleConfig]
    truong: list[TruongInfo]
    diem_toi_da_moi_luat: int = DIEM_TOI_DA_MOI_LUAT
    nguong_vo_cuc: float = NGUONG_VO_CUC


class RulesUpdate(BaseModel):
    rules: list[RuleConfig]


def _dung_rules_response() -> RulesResponse:
    return RulesResponse(
        rules=[RuleConfig(**c) for c in reload()["rules"]],
        truong=[TruongInfo(**t) for t in mo_ta_danh_muc()],
    )


def _loi(chi_tiet: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=chi_tiet
    )


def _kiem_tra_bo_luat(rules: list[RuleConfig]) -> None:
    """Ràng buộc liên trường và ràng buộc với danh mục — Pydantic không kiểm được."""
    if not rules:
        raise _loi("phải có ít nhất một luật")

    ma = [r.ma for r in rules]
    trung = {m for m in ma if ma.count(m) > 1}
    if trung:
        raise _loi(f"mã luật bị trùng: {sorted(trung)}")

    if not any(r.bat for r in rules):
        raise _loi(
            "phải bật ít nhất một luật, nếu không hệ thống không còn cơ sở chấm điểm"
        )

    for r in rules:
        truong = DANH_MUC_TRUONG.get(r.truong)
        if truong is None:
            raise _loi(
                f"luật {r.ma}: trường '{r.truong}' không có trong danh mục trường "
                f"(xem GET /config/rules → truong)"
            )

        # Trường phân loại cần bang_diem với đúng tập giá trị; trường số cần bac.
        if truong.kieu == "phan_loai":
            if r.bang_diem is None:
                raise _loi(
                    f"luật {r.ma}: trường '{r.truong}' là phân loại, phải có 'bang_diem'"
                )
            if r.bac is not None:
                raise _loi(f"luật {r.ma}: trường phân loại không dùng 'bac'")
            if set(r.bang_diem) != set(truong.gia_tri_hop_le):
                raise _loi(
                    f"luật {r.ma}: bảng điểm phải có đúng các khóa {list(truong.gia_tri_hop_le)}"
                )
            continue

        if r.bac is None:
            raise _loi(f"luật {r.ma}: trường '{r.truong}' là số, phải có 'bac'")
        if r.bang_diem is not None:
            raise _loi(f"luật {r.ma}: trường số không dùng 'bang_diem'")
        nguong = [n for n, _ in r.bac]
        if len(nguong) != len(set(nguong)):
            raise _loi(f"luật {r.ma}: các bậc không được trùng ngưỡng nhau")
        dung_thu_tu = (
            nguong == sorted(nguong)
            if r.nghich_dao
            else nguong == sorted(nguong, reverse=True)
        )
        if not dung_thu_tu:
            chieu = "tăng dần" if r.nghich_dao else "giảm dần"
            raise _loi(f"luật {r.ma}: ngưỡng phải {chieu} để bậc đầu là bậc tốt nhất")


@router.get("/rules", response_model=RulesResponse)
async def get_rules_config():
    """Đọc bộ luật chấm điểm hiện tại kèm danh mục trường được phép dùng."""
    return _dung_rules_response()


@router.put("/rules", response_model=RulesResponse)
async def update_rules_config(body: RulesUpdate):
    """Thay toàn bộ bộ luật: thêm, sửa, xoá, sắp xếp, bật/tắt trong một lần lưu."""
    _kiem_tra_bo_luat(body.rules)

    current = reload()
    current["rules"] = [r.model_dump(exclude_none=True) for r in body.rules]
    save(current)
    return _dung_rules_response()
