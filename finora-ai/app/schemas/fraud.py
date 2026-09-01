"""
Schema Pydantic cho API phát hiện gian lận giao dịch ví.

Request mô tả **một** giao dịch ví cùng phần lịch sử tối thiểu của ví nhận.

Vì sao lịch sử ví nhận phải do người gọi truyền sang thay vì `finora-ai` tự tra:
theo `.agents/rules/07-service-boundaries.md`, `finora-ai` không sở hữu
wallet/transaction và MUST NOT đọc database của `finora-payment`. Ba trường
`dest_*_truoc_do` chính là "payment behavior contract" mà roadmap P7-B05 ghi là
phụ thuộc của task này.

Nguyên tắc về trường tùy chọn giống `schemas/credit.py`: mặc định là `None`, KHÔNG
phải một con số. Đặt mặc định là số thì bộ dự đoán không bao giờ nhìn thấy giá trị
thiếu, và median trong gói model trở nên vô dụng.
"""

from typing import Literal

from pydantic import BaseModel, Field

LOAI_GIAO_DICH_HOP_LE = Literal["CASH_IN", "CASH_OUT", "DEBIT", "PAYMENT", "TRANSFER"]

MUC_RUI_RO_HOP_LE = Literal["THAP", "TRUNG_BINH", "CAO"]


class FraudDetectRequest(BaseModel):
    """Một giao dịch ví cần chấm rủi ro gian lận."""

    # ── Bắt buộc ──────────────────────────────────────────────────────────────
    ma_giao_dich: str = Field(
        min_length=1,
        max_length=64,
        description="Mã giao dịch phía Payment, dùng để đối chiếu kết quả",
    )
    loai_giao_dich: LOAI_GIAO_DICH_HOP_LE = Field(description="Loại giao dịch ví")
    so_tien: float = Field(gt=0, description="Số tiền giao dịch")
    so_du_truoc_gui: float = Field(
        ge=0, description="Số dư ví gửi ngay trước giao dịch"
    )
    gio_trong_ngay: int = Field(
        ge=0, le=23, description="Giờ thực hiện giao dịch (0-23, giờ địa phương)"
    )

    # ── Tùy chọn ──────────────────────────────────────────────────────────────
    so_du_truoc_nhan: float | None = Field(
        default=None,
        ge=0,
        description=(
            "Số dư ví nhận ngay trước giao dịch. Bỏ trống khi đích đến nằm ngoài "
            "hệ thống ví (rút về ngân hàng)."
        ),
    )
    ngay_trong_thang: int | None = Field(
        default=None,
        ge=0,
        le=31,
        description="Ngày trong tháng, phục vụ đặc trưng chu kỳ",
    )

    # ── Payment behavior contract — lịch sử ví nhận ───────────────────────────
    dest_so_lan_nhan_truoc_do: int | None = Field(
        default=None,
        ge=0,
        description="Số lần ví nhận đã nhận tiền trước giao dịch này",
    )
    dest_tong_tien_nhan_truoc_do: float | None = Field(
        default=None,
        ge=0,
        description="Tổng tiền ví nhận đã nhận trước giao dịch này",
    )
    dest_so_nguoi_gui_khac_nhau_truoc_do: int | None = Field(
        default=None,
        ge=0,
        description=(
            "Số ví gửi khác nhau đã từng chuyển tới ví nhận. Nhiều người gửi lạ "
            "dồn vào một ví là dấu hiệu tài khoản trung gian (mule account)."
        ),
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "ma_giao_dich": "TXN-2026-0001",
                "loai_giao_dich": "TRANSFER",
                "so_tien": 181000.0,
                "so_du_truoc_gui": 181000.0,
                "so_du_truoc_nhan": 0.0,
                "gio_trong_ngay": 2,
                "ngay_trong_thang": 15,
                "dest_so_lan_nhan_truoc_do": 0,
                "dest_tong_tien_nhan_truoc_do": 0.0,
                "dest_so_nguoi_gui_khac_nhau_truoc_do": 0,
            }
        }
    }


class BangChung(BaseModel):
    """Một dòng bằng chứng: đặc trưng nào đẩy giao dịch về phía nghi ngờ."""

    dac_trung: str = Field(description="Tên đặc trưng trong gói model")
    mo_ta: str = Field(description="Diễn giải tiếng Việt của đặc trưng")
    gia_tri: float = Field(description="Giá trị đặc trưng của chính giao dịch này")
    muc_dong_gop: float = Field(
        description="Đóng góp TreeSHAP vào log-odds; càng lớn càng đẩy về phía gian lận"
    )


class FraudDetectResponse(BaseModel):
    """Kết quả chấm rủi ro gian lận.

    Response CHỈ chứa kết quả kỹ thuật. Không có trường hành động (chặn, giữ tiền,
    khóa ví) vì theo `07-service-boundaries.md` những quyết định đó thuộc
    `finora-payment`; `finora-ai` chỉ cung cấp điểm số và bằng chứng.
    """

    ma_giao_dich: str
    fraud_probability: float = Field(
        description="Xác suất gian lận do mô hình dự đoán (0-1)"
    )
    muc_rui_ro: MUC_RUI_RO_HOP_LE = Field(
        description="Mức rủi ro kỹ thuật quy từ xác suất quanh ngưỡng của gói model"
    )
    nguong_quyet_dinh: float = Field(
        description="Ngưỡng cắt lưu trong gói model, để Payment tự áp policy"
    )
    vuot_nguong: bool = Field(description="Xác suất có vượt ngưỡng của gói model không")
    bang_chung: list[BangChung] = Field(
        default_factory=list,
        description="Các đặc trưng đẩy điểm lên phía gian lận, sắp xếp giảm dần",
    )
    da_cham_bang_mo_hinh: bool = Field(
        description=(
            "False khi loại giao dịch nằm ngoài phạm vi mô hình — kết quả đến từ "
            "quy tắc phạm vi, không phải từ mô hình."
        )
    )
    model_version: str
