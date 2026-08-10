"""
Schema Pydantic cho API chấm điểm tín dụng.

Chỉ nhận dữ liệu FINORA thực sự thu thập được: hồ sơ tự khai trên app + eKYC/CCCD.
Không có trường nào từ CIC hay FICO — FINORA chưa có kết nối API tới CIC, nên nhận
những trường đó vào là hứa hẹn một khả năng không tồn tại.

Nguyên tắc quan trọng về trường tùy chọn: mặc định là `None`, KHÔNG phải một con số.
Nếu đặt mặc định là số (ví dụ `person_age: int = 30`), bộ dự đoán sẽ không bao giờ
nhìn thấy giá trị thiếu và median trong gói model trở nên vô dụng — người vay bỏ
trống sẽ được chấm bằng một con số bịa ra ở tầng schema thay vì giá trị mà mô hình
thực sự đã học. Để `None` thì `BoDuDoan.chuan_bi_dac_trung()` mới điền được từ
`median_dien_thieu`.
"""
from typing import Literal

from pydantic import BaseModel, Field

PURPOSE_HOP_LE = Literal[
    "debt_consolidation", "credit_card", "home_improvement", "major_purchase",
    "medical", "car", "small_business", "moving", "vacation", "education", "other",
]

HOME_OWNERSHIP_HOP_LE = Literal["RENT", "OWN", "MORTGAGE", "OTHER"]
VERIFICATION_STATUS_HOP_LE = Literal["Verified", "Source Verified", "Not Verified"]
INTEREST_METHOD_HOP_LE = Literal[
    "FLAT", "DECLINING_BALANCE", "DECLINING_BALANCE_RECALC"
]


class CreditScoreRequest(BaseModel):
    """Hồ sơ vay cần chấm điểm. Đơn vị tiền tệ: VNĐ."""

    # ── Bắt buộc ──────────────────────────────────────────────────────────────
    annual_inc: float = Field(gt=0, description="Thu nhập năm khai báo (VNĐ)")
    loan_amnt: float = Field(ge=1, description="Số tiền vay yêu cầu (VNĐ)")
    purpose: PURPOSE_HOP_LE = Field(description="Mục đích vay")
    home_ownership: HOME_OWNERSHIP_HOP_LE = Field(description="Tình trạng nhà ở")

    # ── Tùy chọn — bỏ trống thì điền bằng median trong gói model ───────────────
    person_age: int | None = Field(
        default=None, ge=18, le=80, description="Tuổi lấy từ CCCD qua eKYC"
    )
    emp_length: str | None = Field(
        default=None, description='Thâm niên việc làm. Vd "10+ years", "5 years", "< 1 year"'
    )
    int_rate: float | None = Field(
        default=None, ge=0, description="Lãi suất năm của gói vay (ví dụ: 0.12 đại diện cho 12% hoặc 12.0)"
    )
    term_months: int | None = Field(
        default=None, ge=1, description="Kỳ hạn vay mong muốn (tháng)"
    )
    verification_status: VERIFICATION_STATUS_HOP_LE | None = Field(
        default=None, description="Trạng thái xác thực thu nhập"
    )
    dti: float | None = Field(
        default=None, ge=0, description="Tỷ lệ nợ trên thu nhập (%)"
    )
    delinq_2yrs: int | None = Field(
        default=None, ge=0, description="Số lần trễ hạn thanh toán trên 30 ngày trong 2 năm qua"
    )
    pub_rec: int | None = Field(
        default=None, ge=0, description="Số lượng hồ sơ công khai bất lợi (phá sản, tịch thu tài sản...)"
    )
    installment: float | None = Field(
        default=None,
        ge=0,
        description=(
            "Số tiền phải trả hàng tháng (VNĐ). Bỏ trống thì hệ thống tự tính từ "
            "loan_amnt, int_rate, term_months và interest_method."
        ),
    )
    interest_method: INTEREST_METHOD_HOP_LE | None = Field(
        default="DECLINING_BALANCE",
        description=(
            "Phương pháp tính lãi của gói vay. "
            "FLAT — lãi trên toàn bộ gốc ban đầu suốt kỳ vay, trả nhiều nhất. "
            "DECLINING_BALANCE — lãi trên dư nợ còn lại. "
            "DECLINING_BALANCE_RECALC — như declining, có tính lại lịch khi trả trước hạn."
        ),
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "person_age": 30,
                "emp_length": "5 years",
                "annual_inc": 300_000_000,
                "loan_amnt": 50_000_000,
                "home_ownership": "MORTGAGE",
                "purpose": "debt_consolidation",
                "int_rate": 0.15,
                "term_months": 12,
                "verification_status": "Verified",
                "dti": 15.5,
                "delinq_2yrs": 0,
                "pub_rec": 0,
                "installment": 4500000.0,
            }
        }
    }


class CreditScoreResponse(BaseModel):
    """Kết quả chấm điểm."""

    pd_probability: float = Field(description="Xác suất vỡ nợ do mô hình dự đoán")
    risk_score: int = Field(description="Điểm rủi ro theo quy tắc 5C (0-100)")
    evaluation_score: float = Field(
        description="Điểm tổng hợp = (1-PD)x100 x 0,6 + risk_score x 0,4"
    )
    credit_grade: Literal["A", "B", "C", "D", "E"]
    suggested_limit: int = Field(
        description="Hạn mức đề xuất (VNĐ). Trần 100 triệu/nền tảng theo Nghị định 94/2025"
    )
    decision: Literal["APPROVED", "PENDING_REVIEW", "REJECTED"]
    rejection_reason: str | None = Field(default=None, description="Lý do từ chối nếu bị chốt chặn cứng vi phạm")
    model_version: str
