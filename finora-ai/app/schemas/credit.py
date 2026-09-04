"""
Schema Pydantic cho API chấm điểm tín dụng.

Nhận dữ liệu FINORA thu thập được: hồ sơ tự khai trên app + eKYC/CCCD.
CIC data (điểm + dữ liệu thô) được lấy tự động qua cic-service khi có so_cccd —
không cần truyền vào request.

Trường int_rate và term_months là thông tin sản phẩm vay từ Fineract, optional vì
không phải mọi luồng đều có sẵn lúc scoring.

Nguyên tắc quan trọng về trường tùy chọn: mặc định là `None`, KHÔNG phải một con số.
Nếu đặt mặc định là số, bộ dự đoán sẽ không bao giờ nhìn thấy giá trị thiếu và
median trong gói model trở nên vô dụng.
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
    verification_status: VERIFICATION_STATUS_HOP_LE | None = Field(
        default=None, description="Trạng thái xác thực thu nhập"
    )
    dti: float | None = Field(
        default=None, ge=0, description="Tỷ lệ nợ trên thu nhập (%)"
    )
    installment: float | None = Field(
        default=None,
        ge=0,
        description=(
            "Số tiền phải trả hàng tháng (VNĐ). Do bên gọi tính (Loan lấy từ lịch trả "
            "Fineract) rồi gửi sang — service KHÔNG tự tính. Bỏ trống thì điền median "
            "từ gói model như mọi trường tùy chọn khác."
        ),
    )
    int_rate: float | None = Field(
        default=None, ge=0, le=100,
        description="Lãi suất danh nghĩa (%/năm) từ sản phẩm Fineract",
    )
    term_months: int | None = Field(
        default=None, ge=1, le=24,
        description="Kỳ hạn vay (tháng). Tối đa 24 theo NĐ 94/2025",
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

    # ── Tra cứu CIC ──────────────────────────────────────────────────────────
    so_cccd: str | None = Field(
        default=None,
        min_length=12,
        max_length=12,
        pattern=r"^\d{12}$",
        description="Số CCCD 12 chữ số. Có thì tra điểm CIC, không có thì bỏ qua.",
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
                "verification_status": "Verified",
                "dti": 15.5,
                "installment": 4500000.0,
                "int_rate": 12.0,
                "term_months": 12,
                "so_cccd": "012345678901",
            }
        }
    }


class RuleTraceItem(BaseModel):
    """Vết của một luật đã chạy — cơ sở giải trình quyết định cho người vay."""

    ma: str = Field(description="Mã luật, vd CHARACTER_CIC_HISTORY")
    nhom_5c: str = Field(description="Nhóm 5C: Character / Capacity / Capital")
    mo_ta: str = Field(description="Mô tả luật bằng tiếng Việt")
    gia_tri: float | str | None = Field(description="Giá trị luật đọc được, None nếu thiếu")
    diem: int = Field(description="Điểm luật này cộng vào risk_score")
    toi_da: int = Field(description="Điểm tối đa của luật")
    thieu_du_lieu: bool = Field(description="True khi phải dùng điểm trung tính vì thiếu dữ liệu")


class CreditScoreResponse(BaseModel):
    """Kết quả chấm điểm."""

    pd_probability: float = Field(description="Xác suất vỡ nợ do mô hình dự đoán")
    risk_score: int = Field(description="Điểm rủi ro theo quy tắc 5C (0-100)")
    evaluation_score: float = Field(
        description=(
            "Điểm tổng hợp = (1-PD)x100 x pd_weight + risk_score x risk_weight. "
            "Trọng số đọc từ config/product_config.json (model_weights)."
        )
    )
    credit_grade: Literal["A", "B", "C", "D", "E"]
    suggested_limit: int = Field(
        description=(
            "Hạn mức đề xuất (VNĐ). Trần 100 triệu/khách hàng/nền tảng theo "
            "Quyết định 2866/QĐ-NHNN ngày 22/7/2025"
        )
    )
    decision: Literal["APPROVED", "PENDING_REVIEW", "REJECTED"]
    rejection_reason: str | None = Field(
        default=None,
        description="Mã vi phạm đầu tiên. Giữ lại cho tương thích ngược — dùng rejection_reasons.",
    )
    rejection_reasons: list[str] = Field(
        default_factory=list,
        description=(
            "Toàn bộ mã chốt chặn cứng bị vi phạm. Trả hết thay vì dừng ở lỗi đầu để "
            "người vay sửa một lần, không phải quay lại nhiều vòng."
        ),
    )
    rule_trace: list[RuleTraceItem] = Field(
        default_factory=list,
        description="Vết từng luật đã chạy — mỗi điểm cộng đều truy ngược được về một luật có tên.",
    )
    model_version: str


class YeuToAnhHuong(BaseModel):
    """Một yếu tố ảnh hưởng tới PD, đo bằng đóng góp TreeSHAP."""

    dac_trung: str = Field(description="Tên đặc trưng trong gói model")
    mo_ta: str = Field(description="Diễn giải tiếng Việt của đặc trưng")
    gia_tri: float = Field(
        description="Giá trị đặc trưng của chính hồ sơ này, sau khi điền median nếu thiếu"
    )
    muc_dong_gop: float = Field(
        description=(
            "Đóng góp TreeSHAP vào log-odds vỡ nợ. Dương = đẩy hồ sơ về phía rủi ro "
            "cao hơn, âm = kéo về phía an toàn hơn."
        )
    )
    la_leakage: bool = Field(
        default=False,
        description=(
            "True khi đặc trưng là target leakage đã biết (int_rate). Vẫn hiển thị để "
            "mô tả trung thực mô hình, nhưng KHÔNG được dùng giải thích cho người vay."
        ),
    )


class YeuToGop(BaseModel):
    """Một dữ kiện gốc của hồ sơ, gộp từ mọi đặc trưng dẫn xuất của nó."""

    ma_nhom: str = Field(description="Mã nhóm dữ kiện, vd du_no, diem_cic")
    mo_ta: str = Field(description="Tên dữ kiện bằng tiếng Việt")
    muc_do: Literal["manh", "vua", "nhe"] = Field(
        description="Mức ảnh hưởng so với dữ kiện mạnh nhất của chính hồ sơ này"
    )
    muc_dong_gop: float = Field(
        description="Tổng đóng góp SHAP của cả nhóm trên thang log-odds, để đối chứng"
    )


class TomTatYeuTo(BaseModel):
    """Bản gộp cho thẩm định viên: tối đa ba dữ kiện mỗi chiều, không có lãi suất.

    Bản thô chia một dữ kiện (dư nợ, thu nhập) ra nhiều đặc trưng trái dấu nhau nên
    không đọc được. Bản này cộng lại theo dữ kiện gốc — hợp lệ vì SHAP cộng dồn.
    """

    bat_loi: list[YeuToGop] = Field(default_factory=list, description="Đẩy rủi ro lên")
    co_loi: list[YeuToGop] = Field(default_factory=list, description="Kéo rủi ro xuống")


class GiaiThichMoHinh(BaseModel):
    """Phần giải thích của mô hình ML — vì sao mô hình cho hồ sơ này PD như vậy."""

    yeu_to_bat_loi: list[YeuToAnhHuong] = Field(
        default_factory=list,
        description="Yếu tố đẩy PD lên, sắp xếp theo độ lớn đóng góp giảm dần.",
    )
    yeu_to_co_loi: list[YeuToAnhHuong] = Field(
        default_factory=list,
        description="Yếu tố kéo PD xuống, sắp xếp theo độ lớn đóng góp giảm dần.",
    )
    gia_tri_co_so: float = Field(
        description=(
            "Bias của mô hình trên thang log-odds. Tổng mọi đóng góp cộng giá trị này "
            "bằng log-odds đầu ra — dùng để kiểm chứng tính cộng dồn của SHAP."
        )
    )
    canh_bao: list[str] = Field(
        default_factory=list,
        description="Cảnh báo về chất lượng giải thích, vd đặc trưng leakage xuất hiện.",
    )
    tom_tat: TomTatYeuTo = Field(
        description="Bản gộp theo dữ kiện gốc, dành cho thẩm định viên đọc."
    )


class DienGiaiNguoiDung(BaseModel):
    """Phần diễn giải dành cho người vay đọc, không phải cho kỹ sư.

    Tồn tại vì đóng góp TreeSHAP trên thang log-odds là con số đúng nhưng vô nghĩa
    với người nhận quyết định. Một lời giải thích chỉ hoàn thành nhiệm vụ khi người
    đọc hiểu được và biết phải làm gì tiếp theo.
    """

    thong_diep: str = Field(description="Một câu tóm tắt quyết định bằng tiếng Việt")
    ly_do_chinh: list[str] = Field(
        default_factory=list,
        description=(
            "Vì sao ra quyết định đó. Vi phạm chốt chặn pháp lý được nêu trước vì "
            "đó là lý do thật sự, mọi phân tích điểm số phía sau không đổi được."
        ),
    )
    goi_y_cai_thien: list[str] = Field(
        default_factory=list,
        description=(
            "Việc cần làm để lần sau khá hơn, kèm mốc cụ thể suy ra từ ngưỡng thật "
            "trong Rule Engine. Tối đa ba gợi ý, ưu tiên luật đang mất nhiều điểm nhất."
        ),
    )


class CreditExplainResponse(BaseModel):
    """Giải thích đầy đủ một quyết định chấm điểm (C1.2).

    Gộp cả hai nửa của quyết định: `giai_thich_mo_hinh` nói vì sao mô hình ML cho
    PD đó, `rule_trace` nói vì sao Rule Engine 5C cộng/trừ điểm. Trả riêng lẻ chỉ
    một nửa sẽ không giải thích được `evaluation_score` vì điểm này trộn cả hai.
    """

    pd_probability: float = Field(description="Xác suất vỡ nợ do mô hình dự đoán")
    risk_score: int = Field(description="Điểm rủi ro theo quy tắc 5C (0-100)")
    evaluation_score: float = Field(description="Điểm tổng hợp của PD và risk_score")
    credit_grade: Literal["A", "B", "C", "D", "E"]
    decision: Literal["APPROVED", "PENDING_REVIEW", "REJECTED"]
    dien_giai: DienGiaiNguoiDung = Field(
        description="Bản diễn giải cho người vay — thông điệp, lý do và gợi ý cải thiện."
    )
    giai_thich_mo_hinh: GiaiThichMoHinh = Field(
        description="Giải thích PD bằng TreeSHAP — nửa ML của quyết định."
    )
    rule_trace: list[RuleTraceItem] = Field(
        default_factory=list,
        description="Vết luật 5C — nửa quy tắc của quyết định.",
    )
    rejection_reasons: list[str] = Field(
        default_factory=list,
        description="Mã chốt chặn cứng bị vi phạm, nếu có.",
    )
    model_version: str
