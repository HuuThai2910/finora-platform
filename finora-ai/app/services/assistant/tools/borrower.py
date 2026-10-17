"""Công cụ chỉ đọc của Trợ lý FINORA cho người vay (AI-CHAT-001, spec mục 8.1).

Mỗi công cụ gọi API của service sở hữu dữ liệu bằng token của chính người hỏi (service đích tự kiểm
chủ sở hữu, nên hỏi hồ sơ của người khác chỉ nhận 403/404), rồi đi qua một mapper "danh sách trắng":
chỉ những trường spec cho phép mới vào `facts` — phần được gửi cho nhà cung cấp LLM ở nước ngoài.
Phần còn lại hoặc bỏ hẳn (thu nhập, DTI, `actorId`, `borrowerId`), hoặc chỉ hiện trên thẻ cho chính
người dùng xem (lý do từ chối dạng mã, dư nợ, số lần nhập PIN còn lại, trạng thái ví).

Vì sao danh sách trắng thay vì danh sách đen: DTO của Loan được thêm trường thường xuyên. Danh sách
đen sẽ để lọt trường mới ra ngoài mà không ai hay; danh sách trắng thì trường mới mặc định bị bỏ.

Trường chữ tự do (`purposeDetail`, `reasonDetail`, `description` của sản phẩm, lý do rút hồ sơ) không
bao giờ vào `facts`: người vay hoặc admin có thể gõ "bỏ qua mọi chỉ dẫn…" vào đó (chèn lệnh).

Tiền: Loan/Payment trả `BigDecimal` thành JSON number. Mọi số đi qua `Decimal(str(x))` trước khi so
sánh hay định dạng, để sai số của float không làm lệch con số hiển thị so với app.

Phụ lục (Hải duyệt 2026-10-16): `goi_y_khoan_vay` so sánh tối đa 3 gói vay cho số tiền/kỳ hạn người
vay nhập — cùng tinh thần "code tính, LLM không tính" như `mo_phong_khoan_vay`. Không có SuggestionDef
riêng (chip không mang được amount/termMonths/monthlyIncome): tool chỉ gọi được qua đường LLM có tool.
"""

from __future__ import annotations

import asyncio
import re
from collections.abc import Mapping, Sequence
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from app.schemas.assistant import Action, Card, CardItem, Citation
from app.services.assistant.deeplinks import build_action
from app.services.assistant.formatting import (
    ngay_gio_vn,
    ngay_vn,
    phan_tram,
    so_nguyen,
    tien_vnd,
)
from app.services.assistant.glossary import (
    dich_danh_sach_ly_do,
    dich_ly_do,
    dich_trang_thai,
    giai_thich_trang_thai,
    nhom_no_tu_dpd,
)
from app.services.assistant.llm.providers import Lane
from app.services.assistant.principal import Toolset
from app.services.assistant.relay import FinoraApiClient, ServiceName
from app.services.assistant.tools.base import (
    NoParams,
    RouteRule,
    ScreenTool,
    SuggestionDef,
    ToolError,
    ToolResult,
    ToolSpec,
)
from app.services.credit import product_config

# ---------------------------------------------------------------------------
# Mã nghiệp vụ và đường dẫn được phép gọi
# ---------------------------------------------------------------------------

# Loan sinh mã hồ sơ "LA-" + 20 ký tự hex in hoa (ApplicationNumberGenerator). Test và dữ liệu mẫu của
# Loan còn dùng dạng "LA-TEST-001", nên chấp nhận chữ in hoa/số/gạch nối, tổng tối đa 50 ký tự như cột
# DB. Không có "/", "." hay "%" nên mã không thể biến đường dẫn thành đường dẫn khác.
MA_HO_SO_REGEX = r"LA-[A-Z0-9][A-Z0-9-]{0,46}"
_MA_HOP_DONG_REGEX = r"LC-[A-Z0-9][A-Z0-9-]{0,46}"
_MA_KHOAN_VAY_REGEX = r"LN-[A-Z0-9][A-Z0-9-]{0,46}"
_MA_SAN_PHAM_REGEX = r"[1-9][0-9]{0,17}"

_R_DS_HO_SO = RouteRule(method="GET", service=ServiceName.LOAN, pattern=r"/api/v1/loan-applications/me")
_R_HO_SO = RouteRule(method="GET", service=ServiceName.LOAN, pattern=rf"/api/v1/loan-applications/{MA_HO_SO_REGEX}")
_R_LICH_SU = RouteRule(
    method="GET", service=ServiceName.LOAN, pattern=rf"/api/v1/loan-applications/{MA_HO_SO_REGEX}/history"
)
_R_HOP_DONG = RouteRule(method="GET", service=ServiceName.LOAN, pattern=r"/api/v1/loan-contracts/me")
_R_KHOAN_VAY = RouteRule(method="GET", service=ServiceName.LOAN, pattern=r"/api/v1/loans/me")
_R_VI = RouteRule(method="GET", service=ServiceName.PAYMENT, pattern=r"/api/v1/wallets/me")
_R_PIN = RouteRule(method="GET", service=ServiceName.USER, pattern=r"/api/v1/users/me/pin")
_R_SAN_PHAM = RouteRule(method="GET", service=ServiceName.LOAN, pattern=r"/api/v1/loan-products")
_R_MO_PHONG = RouteRule(
    method="POST", service=ServiceName.LOAN, pattern=rf"/api/v1/loan-products/{_MA_SAN_PHAM_REGEX}/repayment-previews"
)

# Việt Nam không đổi giờ theo mùa nên dùng độ lệch cố định, khỏi phụ thuộc gói tzdata trên Windows.
_GIO_VN = timezone(timedelta(hours=7))

# Màn tính thử của app cũng giả định giải ngân sau 30 ngày (finora-mobile/src/features/products/constant.ts);
# dùng cùng giả định để con số bot đưa ra khớp con số người vay thấy trên app.
_SO_NGAY_GIAI_NGAN_GIA_DINH = 30

# goi_y_khoan_vay giả định giải ngân gần hơn (7 ngày): người hỏi đang cân nhắc chọn gói, khác với màn
# tính thử một sản phẩm cụ thể đã biết chắc sẽ nộp (mo_phong_khoan_vay dùng mốc 30 ngày của app).
_SO_NGAY_GIAI_NGAN_GOI_Y = 7

# Lịch sử gửi LLM chỉ lấy vài bước gần nhất: đủ thấy hồ sơ vừa đi qua đâu mà giữ dữ kiện dưới ~2.000 token.
_LICH_SU_GUI_LLM = 8

_LOI_DINH_DANG = "Dữ liệu trả về không đúng định dạng, bạn thử lại sau ít phút."
KHONG_CO_HO_SO = "Bạn chưa có hồ sơ nào."
KHONG_CO_HOP_DONG = "Bạn chưa có hợp đồng vay nào."
KHONG_CO_KHOAN_VAY = "Bạn chưa có khoản vay nào đã giải ngân."
KHONG_CO_SAN_PHAM = "FINORA hiện chưa mở bán sản phẩm vay nào."

# ---------------------------------------------------------------------------
# Bảng dịch nhỏ chỉ người vay dùng (glossary giữ các nhóm trạng thái dùng chung)
# ---------------------------------------------------------------------------

_NGUON_QUYET_DINH = {"AI_POLICY": "Tự động theo chính sách chấm điểm", "ADMIN": "Chuyên viên thẩm định"}
_CACH_TRA = {"ANNUITY": "Trả góp đều hằng kỳ", "EQUAL_PRINCIPAL": "Gốc đều, lãi giảm dần"}
_TEN_NHOM_NO = {
    1: "nhóm 1 (nợ đủ tiêu chuẩn)",
    2: "nhóm 2 (nợ cần chú ý)",
    3: "nhóm 3 (nợ dưới tiêu chuẩn)",
    4: "nhóm 4 (nợ nghi ngờ)",
    5: "nhóm 5 (nợ có khả năng mất vốn)",
}
_TRANG_THAI_VI = {"FROZEN": "Ví đang bị tạm khóa", "CLOSED": "Ví đã đóng"}
_CAU_NGUON_QUYET_DINH = {
    _NGUON_QUYET_DINH["AI_POLICY"]: "Quyết định được đưa ra tự động theo chính sách chấm điểm của FINORA.",
    _NGUON_QUYET_DINH["ADMIN"]: "Quyết định do chuyên viên thẩm định đưa ra sau khi xem hồ sơ.",
}

# Ý nghĩa và bước kế tiếp của từng trạng thái hồ sơ, viết lại từ APPLICATION_STATUS của app
# (finora-mobile/src/features/applications/constant.ts) để bot và app nói cùng một điều.
_BUOC_TIEP_THEO_HO_SO = {
    "SUBMITTED": "FINORA đã nhận hồ sơ; bước tiếp theo là kiểm tra điều kiện vay.",
    "ELIGIBILITY_PENDING": "Hệ thống đang đối chiếu hồ sơ với điều kiện của sản phẩm, sau đó sẽ chấm điểm tín dụng.",
    "SCORING": "Mô hình tín dụng đang đánh giá hồ sơ, việc này thường mất vài phút.",
    "SCORING_RETRY_PENDING": "Lần chấm điểm trước chưa hoàn tất nên hệ thống sẽ tự chạy lại; bạn không cần làm gì.",
    "PENDING_REVIEW": "Chuyên viên đang xem hồ sơ. Đây là bước bình thường, không phải lỗi.",
    "APPROVED": "Hồ sơ đã được duyệt: tiếp theo là gọi vốn từ nhà đầu tư rồi ký hợp đồng, chưa phải giải ngân.",
    "REJECTED": "Hồ sơ không được duyệt; bạn có thể nộp hồ sơ mới khi điều kiện thay đổi.",
    "WITHDRAWN": "Bạn đã rút hồ sơ này nên FINORA dừng xử lý.",
}

_HD_CHO_BAN_KY = ("PENDING_BORROWER_SIGNATURE", "PENDING_SIGNATURE")
_HD_DANG_KY = (*_HD_CHO_BAN_KY, "PENDING_LENDER_SIGNATURES", "SIGNING")
_KHOAN_DANG_TRA = ("ACTIVE", "RESTRUCTURING")

# Khi AI tự từ chối, Loan ghi danh sách mã chốt chặn vào reasonDetail dạng "MA_A, MA_B"
# (CreditScoringStateService.complete). Chỉ chuỗi đúng khuôn này mới được dịch; chữ tự do bị bỏ.
_DANH_SACH_MA_LY_DO = re.compile(r"[A-Z][A-Z0-9_]{1,63}(?:\s*,\s*[A-Z][A-Z0-9_]{1,63})*")

# Ngưỡng cảnh báo tỷ lệ trả nợ/thu nhập của goi_y_khoan_vay đọc từ luật CAPACITY_INSTALLMENT_BURDEN
# trong config/product_config.json (bậc điểm [0.2, 15] là mốc "tạm ổn" cuối cùng trước khi rơi xuống
# 8 điểm); không đọc được thì dùng mặc định 0,5 và ghi chú lại trong facts để không im lặng sai lệch.
_MA_LUAT_TY_LE_TRA_NO = "CAPACITY_INSTALLMENT_BURDEN"
_NGUONG_TY_LE_TRA_NO_MAC_DINH = Decimal("0.5")


def _nguong_ty_le_tra_no() -> tuple[Decimal, bool]:
    """Mốc tỷ lệ (trả/tháng + nợ khác) / thu nhập mà từ đó luật chấm điểm bắt đầu trừ điểm nặng.

    Đọc bậc thứ hai của luật `CAPACITY_INSTALLMENT_BURDEN` (bậc đầu là "tốt", bậc thứ hai là mốc
    cuối cùng còn được coi là tạm ổn trước khi tụt xuống mức đáng lo). Không tìm thấy luật/bậc hợp
    lệ -> trả mặc định 0,5 kèm cờ "mặc_định" để tool tự ghi chú, không báo sai số đã đọc được.
    """
    try:
        for rule in product_config.get_rules():
            if rule.get("ma") != _MA_LUAT_TY_LE_TRA_NO:
                continue
            bac = rule.get("bac")
            if isinstance(bac, list) and len(bac) >= 2:
                return Decimal(str(bac[1][0])), False
    except (KeyError, TypeError, ValueError, OSError):
        pass
    return _NGUONG_TY_LE_TRA_NO_MAC_DINH, True


# ---------------------------------------------------------------------------
# Tham số công cụ
# ---------------------------------------------------------------------------


class _ThamSo(BaseModel):
    """Tham số công cụ: JSON camelCase (đúng tên LLM thấy), Python snake_case; khóa lạ bị từ chối."""

    model_config = ConfigDict(extra="forbid", alias_generator=to_camel, populate_by_name=True)


class ChiTietHoSoParams(_ThamSo):
    application_number: str = Field(
        pattern=rf"^{MA_HO_SO_REGEX}$", description="Mã hồ sơ vay dạng LA-…, lấy từ dữ liệu đã có hoặc màn đang mở"
    )

    @field_validator("application_number", mode="before")
    @classmethod
    def _chuan_hoa_ma(cls, value: Any) -> Any:
        # LLM hay chép mã thành chữ thường hoặc dính khoảng trắng. Chuẩn hóa trước khi so regex để
        # người dùng không phải hỏi lại chỉ vì khác hoa/thường; mã sai khuôn vẫn bị chặn.
        return value.strip().upper() if isinstance(value, str) else value


def _ma_viet_hoa(value: Any) -> Any:
    return value.strip().upper() if isinstance(value, str) else value


class KhoanDangMoParams(_ThamSo):
    """Mã khoản vay đang mở trên màn (LoanServicingDetail gửi `loanNumber`); bỏ trống thì xét mọi khoản.

    Không phải bộ lọc quyền: công cụ vẫn chỉ đọc `/loans/me`, mã không thuộc người hỏi thì bị bỏ qua.
    """

    loan_number: str | None = Field(
        default=None, pattern=rf"^{_MA_KHOAN_VAY_REGEX}$",
        description="Mã khoản vay LN-… đang mở trên màn hoặc đã có trong dữ liệu; bỏ trống để xem mọi khoản",
    )

    @field_validator("loan_number", mode="before")
    @classmethod
    def _chuan_hoa_ma(cls, value: Any) -> Any:
        return _ma_viet_hoa(value)


class HopDongDangMoParams(_ThamSo):
    """Mã hợp đồng đang mở trên màn (ContractDetail gửi `contractNumber`); bỏ trống thì xét mọi hợp đồng."""

    contract_number: str | None = Field(
        default=None, pattern=rf"^{_MA_HOP_DONG_REGEX}$",
        description="Mã hợp đồng LC-… đang mở trên màn hoặc đã có trong dữ liệu; bỏ trống để xem mọi hợp đồng",
    )

    @field_validator("contract_number", mode="before")
    @classmethod
    def _chuan_hoa_ma(cls, value: Any) -> Any:
        return _ma_viet_hoa(value)


class MoPhongKhoanVayParams(_ThamSo):
    product_id: int = Field(ge=1, description="Mã sản phẩm vay: trường id trong kết quả san_pham_vay")
    # Sàn dưới 1 triệu để bắt lỗi LLM truyền "30" khi người dùng nói "30 triệu"; lỗi kiểm tham số được
    # gửi lại cho LLM sửa một lần (spec mục 12). Biên thật của từng sản phẩm do Loan kiểm.
    amount: int = Field(ge=1_000_000, le=10_000_000_000, description="Số tiền vay, đơn vị đồng (30 triệu = 30000000)")
    term_months: int = Field(ge=1, le=120, description="Kỳ hạn vay, số tháng")


class GoiYKhoanVayParams(_ThamSo):
    amount: int = Field(ge=1_000_000, le=10_000_000_000, description="Số tiền muốn vay, đơn vị đồng")
    term_months: int = Field(ge=1, le=60, description="Kỳ hạn muốn vay, số tháng")
    monthly_income: int | None = Field(default=None, ge=0, description="Thu nhập mỗi tháng, đơn vị đồng (nếu có)")
    monthly_debt: int | None = Field(
        default=None, ge=0, description="Số tiền phải trả nợ khác mỗi tháng, đơn vị đồng (nếu có)"
    )
    tieu_chi: Literal["TONG_LAI", "TRA_THANG"] = Field(
        default="TONG_LAI", description="Xếp hạng theo tổng lãi (TONG_LAI) hay trả mỗi tháng thấp nhất (TRA_THANG)"
    )


# ---------------------------------------------------------------------------
# Hàm phụ đọc JSON an toàn
# ---------------------------------------------------------------------------


def _so(value: Any) -> Decimal | None:
    """JSON number → Decimal qua str() để giữ đúng chữ số Loan gửi; bool/chuỗi hỏng coi như không có."""
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _chuoi(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    value = value.strip()
    return value or None


def _so_nguyen(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def _doi_tuong(payload: Any) -> Mapping[str, Any]:
    if not isinstance(payload, Mapping):
        raise ToolError("DU_LIEU_KHONG_HOP_LE", _LOI_DINH_DANG)
    return payload


def _con(obj: Mapping[str, Any], key: str) -> Mapping[str, Any] | None:
    value = obj.get(key)
    return value if isinstance(value, Mapping) else None


def _trang(payload: Any) -> tuple[list[Mapping[str, Any]], int]:
    """Đọc PageResponse của Loan `{data, page, size, totalElements}` (dto/common/PageResponse.java)."""
    trang = _doi_tuong(payload)
    data = trang.get("data")
    if not isinstance(data, list):
        raise ToolError("DU_LIEU_KHONG_HOP_LE", _LOI_DINH_DANG)
    items = [item for item in data if isinstance(item, Mapping)]
    tong = _so_nguyen(trang.get("totalElements"))
    return items, tong if tong is not None and tong >= len(items) else len(items)


def _ky_han(value: Any) -> str:
    so_thang = _so_nguyen(value)
    return f"{so_thang} tháng" if so_thang is not None else "—"


def _khoang_ky_han(thap: Any, cao: Any) -> str:
    thap_nhat, cao_nhat = _so_nguyen(thap), _so_nguyen(cao)
    if thap_nhat is None or cao_nhat is None:
        return "—"
    return f"{thap_nhat}–{cao_nhat} tháng"


def _khoang_lai(lai: Mapping[str, str]) -> str:
    return f"{lai['thap_nhat']} – {lai['cao_nhat']} (cơ sở {lai['co_so']})"


def _diem_phan_tram(value: Decimal) -> str:
    """Mức điều chỉnh theo hạng giá tính bằng điểm phần trăm (+1,5 điểm %), không phải % của lãi cơ sở."""
    dau = "+" if value > 0 else "-" if value < 0 else ""
    return f"{dau}{phan_tram(abs(value)).removesuffix('%')} điểm %"


def _nhom_no(dpd: int) -> str:
    nhom = nhom_no_tu_dpd(dpd)
    return _TEN_NHOM_NO.get(nhom, f"nhóm {nhom}") if nhom is not None else "—"


def _la(nhom: str, nhan: Any, *ma: str) -> bool:
    """Câu mẫu chỉ thấy nhãn đã dịch trong facts; so lại qua chính glossary để biết mã gốc."""
    return isinstance(nhan, str) and any(nhan == dich_trang_thai(nhom, m) for m in ma)  # type: ignore[arg-type]


def _the_kv(title: str, items: Sequence[tuple[str, str | None]]) -> Card:
    """Thẻ cặp nhãn–giá trị; dòng không có giá trị thì bỏ để thẻ không đầy dấu gạch."""
    dong = [CardItem(label=nhan, value=gia_tri) for nhan, gia_tri in items if gia_tri]
    return Card(kind="kv", title=title, items=dong)


def _gia_tri_the(result: ToolResult, label: str) -> str | None:
    for card in result.cards:
        for item in card.items:
            if item.label == label:
                return item.value
    return None


def _cau(*phan: str | None) -> str:
    return " ".join(p.strip() for p in phan if p and p.strip())


def _bo_cham(text: str) -> str:
    return text.strip().rstrip(".").strip()


def _hom_nay_vn() -> date:
    """Tách riêng để test cố định ngày (monkeypatch); server Loan chạy giờ UTC nên ngày VN luôn ≥ ngày UTC."""
    return datetime.now(_GIO_VN).date()


def _hanh_dong(key: str, params: Mapping[str, str] | None = None, label: str | None = None) -> Action:
    return build_action(key, dict(params or {}), label)


# ---------------------------------------------------------------------------
# Giai đoạn của hồ sơ đã duyệt (khớp `applicationJourneyStatus` của finora-mobile)
# ---------------------------------------------------------------------------
#
# Loan để hồ sơ ở APPROVED suốt từ lúc duyệt tới sau khi giải ngân; các bước ký/hiệu lực nằm ở LoanContract,
# khoản vay đã giải ngân nằm ở /loans. Chỉ đọc trạng thái hồ sơ thì bot nói "tiếp theo là gọi vốn" cho cả hồ
# sơ đã từ chối điều khoản lẫn hồ sơ đã giải ngân (chạy thật 16/10/2026), trong khi app hiện "Đã từ chối điều
# khoản" / "Đang lập hợp đồng". Thứ tự xét giống app: hợp đồng → điều khoản → gọi vốn; bot xét thêm khoản vay
# trước tiên vì đó là bằng chứng chắc chắn tiền đã giải ngân.

_BUOC_THEO_HOP_DONG: dict[str, str] = {
    "PENDING_LENDER_SIGNATURES": "Khi đủ chữ ký nhà đầu tư, app sẽ mở lượt ký cho bạn.",
    "PENDING_BORROWER_SIGNATURE": "Bạn mở hợp đồng, đọc bản PDF rồi ký bằng mã PIN giao dịch trước hạn.",
    "PENDING_SIGNATURE": "Bạn mở hợp đồng, đọc bản PDF rồi ký bằng mã PIN giao dịch trước hạn.",
    "SIGNING": "Bạn mở ứng dụng SmartCA để xác nhận, rồi quay lại app kiểm tra kết quả.",
    "SIGNED": "Hợp đồng sắp có hiệu lực, sau đó tiền được giải ngân.",
    "EFFECTIVE": "Tiền được giải ngân vào ví theo hợp đồng.",
}
_KHONG_LAP_HOP_DONG = "Nếu vẫn cần vay, bạn nộp hồ sơ mới."


def _giai_doan(ho_so: Mapping[str, Any], hop_dong: Mapping[str, Any] | None,
               khoan: Mapping[str, Any] | None) -> dict[str, str] | None:
    """Giai đoạn hiển thị của hồ sơ APPROVED: nhãn, nghĩa là gì, bước tiếp theo. Trạng thái khác → None."""
    if ho_so.get("status") != "APPROVED":
        return None
    if khoan is not None:
        ma_khoan = str(_chuoi(khoan.get("loanNumber")))
        return {
            "nhan": "Đã giải ngân",
            "y_nghia": (f"Tiền đã được giải ngân thành khoản vay {ma_khoan} "
                        f"({dich_trang_thai('loan', _chuoi(khoan.get('status')))})."),
            "buoc_tiep": "Lịch trả nợ và kỳ tới xem ở mục Khoản vay.",
        }
    if hop_dong is not None:
        ma_tt = _chuoi(hop_dong.get("status"))
        gd = {"nhan": dich_trang_thai("contract", ma_tt), "y_nghia": giai_thich_trang_thai("contract", ma_tt) or ""}
        if ma_tt in _BUOC_THEO_HOP_DONG:
            gd["buoc_tiep"] = _BUOC_THEO_HOP_DONG[str(ma_tt)]
        return gd
    dieu_khoan = (_con(ho_so, "termsConfirmation") or {}).get("status")
    goi_von = (_con(ho_so, "funding") or {}).get("status")
    if dieu_khoan == "PENDING":
        return {"nhan": "Chờ bạn xác nhận",
                "y_nghia": "Điều khoản cuối bất lợi hơn lúc nộp hồ sơ nên FINORA đang chờ quyết định của bạn.",
                "buoc_tiep": "Bạn mở hồ sơ để chấp nhận hoặc từ chối điều khoản cuối trước hạn."}
    if dieu_khoan == "DECLINED":
        return {"nhan": "Đã từ chối điều khoản",
                "y_nghia": "Bạn đã không chấp nhận điều khoản cuối nên FINORA không lập hợp đồng và không giải ngân "
                           "hồ sơ này.",
                "buoc_tiep": _KHONG_LAP_HOP_DONG}
    if dieu_khoan == "EXPIRED":
        return {"nhan": "Hết hạn phản hồi",
                "y_nghia": "Bạn chưa xác nhận điều khoản cuối trong thời hạn nên FINORA không lập hợp đồng cho hồ "
                           "sơ này.",
                "buoc_tiep": _KHONG_LAP_HOP_DONG}
    if goi_von == "EXPIRED":
        return {"nhan": "Gọi vốn không thành công",
                "y_nghia": "Hết thời hạn gọi vốn mà khoản vay chưa đủ vốn từ nhà đầu tư nên FINORA không lập hợp đồng.",
                "buoc_tiep": _KHONG_LAP_HOP_DONG}
    if goi_von == "REQUESTED":
        return {"nhan": "Đang gọi vốn", "y_nghia": "Hồ sơ đã được duyệt và đang chờ nhà đầu tư cấp đủ vốn.",
                "buoc_tiep": "Khi đủ vốn, các nhà đầu tư ký hợp đồng trước rồi tới lượt bạn."}
    if goi_von == "FULLY_FUNDED":
        return {"nhan": "Đang lập hợp đồng",
                "y_nghia": "Khoản vay đã đủ vốn và FINORA đang lập hợp đồng chung cho các bên.",
                "buoc_tiep": "Các nhà đầu tư ký trước, sau đó tới lượt bạn; tiền được giải ngân sau khi hợp đồng có "
                             "hiệu lực."}
    return {"nhan": dich_trang_thai("application", "APPROVED"),
            "y_nghia": "Điều khoản cuối đã được duyệt; hồ sơ sẽ được đưa lên sàn gọi vốn.",
            "buoc_tiep": "Gọi đủ vốn rồi các bên ký hợp đồng; lúc này chưa phải giải ngân."}


def _cau_giai_doan(gd: Mapping[str, str]) -> str:
    return _cau(gd.get("y_nghia"), gd.get("buoc_tiep"))


async def _theo_ho_so(client: FinoraApiClient) -> tuple[dict[str, Mapping[str, Any]], dict[str, Mapping[str, Any]]]:
    """Hợp đồng và khoản vay của người hỏi, xếp theo mã hồ sơ. Lỗi tạm thời → rỗng (chỉ là phần bổ sung)."""
    ket_qua: list[dict[str, Mapping[str, Any]]] = []
    for duong_dan, khoa in (("/api/v1/loan-contracts/me", "contractNumber"), ("/api/v1/loans/me", "loanNumber")):
        theo_ma: dict[str, Mapping[str, Any]] = {}
        try:
            items, _ = _trang(await client.get(ServiceName.LOAN, duong_dan, {"page": 0, "size": 20}))
        except ToolError as exc:
            if exc.code == "PHIEN_HET_HAN":
                raise
            items = []
        for item in items:  # Loan trả mới nhất trước: giữ bản ghi đầu tiên của mỗi hồ sơ
            ma_ho_so = _chuoi(item.get("applicationNumber"))
            if ma_ho_so and _chuoi(item.get(khoa)) and ma_ho_so not in theo_ma:
                theo_ma[ma_ho_so] = item
        ket_qua.append(theo_ma)
    return ket_qua[0], ket_qua[1]


# ---------------------------------------------------------------------------
# ho_so_cua_toi
# ---------------------------------------------------------------------------


async def _ho_so_cua_toi(client: FinoraApiClient, params: NoParams) -> ToolResult:
    payload = await client.get(ServiceName.LOAN, "/api/v1/loan-applications/me", {"page": 0, "size": 10})
    items, tong = _trang(payload)
    hop_dong_theo_ho_so: dict[str, Mapping[str, Any]] = {}
    khoan_theo_ho_so: dict[str, Mapping[str, Any]] = {}
    if any(item.get("status") == "APPROVED" for item in items):
        hop_dong_theo_ho_so, khoan_theo_ho_so = await _theo_ho_so(client)
    ho_so: list[dict[str, Any]] = []
    for item in items:
        ma = _chuoi(item.get("applicationNumber"))
        if ma is None:
            continue
        gd = _giai_doan(item, hop_dong_theo_ho_so.get(ma), khoan_theo_ho_so.get(ma))
        muc: dict[str, Any] = {
            "ma_ho_so": ma,
            "trang_thai": gd["nhan"] if gd else dich_trang_thai("application", _chuoi(item.get("status"))),
            "so_tien_de_nghi": tien_vnd(_so(item.get("requestedAmount"))),
            "ngay_nop": ngay_vn(item.get("submittedAt")),
        }
        if gd:
            muc["giai_thich"] = _cau_giai_doan(gd)
        ho_so.append(muc)
    if not ho_so:
        return ToolResult(facts={"tong_so_ho_so": 0, "ho_so": [], "ghi_chu": KHONG_CO_HO_SO})

    facts: dict[str, Any] = {"tong_so_ho_so": tong, "ho_so": ho_so}
    if tong > len(ho_so):
        facts["ghi_chu"] = f"Chỉ liệt kê {len(ho_so)} hồ sơ gần nhất trên tổng {tong} hồ sơ."
    the = Card(
        kind="table",
        title="Hồ sơ vay của tôi",
        columns=["Mã hồ sơ", "Trạng thái", "Số tiền đề nghị", "Ngày nộp"],
        rows=[[h["ma_ho_so"], h["trang_thai"], h["so_tien_de_nghi"], h["ngay_nop"]] for h in ho_so],
    )
    # Loan sắp danh sách theo createdAt giảm dần nên phần tử đầu là hồ sơ mới nhất.
    nhan = "Mở hồ sơ mới nhất" if len(ho_so) > 1 else None
    return ToolResult(
        facts=facts,
        cards=[the],
        actions=[_hanh_dong("mo_ho_so", {"applicationNumber": ho_so[0]["ma_ho_so"]}, nhan)],
        ids=frozenset(h["ma_ho_so"] for h in ho_so),
    )


# ---------------------------------------------------------------------------
# chi_tiet_ho_so
# ---------------------------------------------------------------------------


def _ly_do_tu_choi(lich_su: Sequence[Mapping[str, Any]]) -> list[str]:
    """Lý do của lần chuyển sang REJECTED gần nhất, đã dịch — chỉ để hiện trên thẻ, không gửi LLM.

    AI tự từ chối: dịch danh sách mã chốt chặn trong reasonDetail (do hệ thống ghi, đúng khuôn mã).
    Còn lại (admin, kiểm tra điều kiện): chỉ dịch reasonCode; reasonDetail là chữ tự do nên bỏ qua.
    """
    for muc in lich_su:  # Loan trả lịch sử mới nhất trước
        if muc.get("toStatus") != "REJECTED":
            continue
        chi_tiet = _chuoi(muc.get("reasonDetail"))
        if muc.get("actorType") == "SYSTEM" and chi_tiet and _DANH_SACH_MA_LY_DO.fullmatch(chi_tiet):
            return [_bo_cham(x) for x in dich_danh_sach_ly_do(chi_tiet)]
        ma = _chuoi(muc.get("reasonCode"))
        return [_bo_cham(dich_ly_do(ma))] if ma else []
    return []


async def _chi_tiet_ho_so(client: FinoraApiClient, params: ChiTietHoSoParams) -> ToolResult:
    ma = params.application_number
    ho_so = _doi_tuong(await client.get(ServiceName.LOAN, f"/api/v1/loan-applications/{ma}"))
    # Lịch sử chỉ bổ sung lý do; lỗi tạm thời ở đây không nên làm hỏng cả câu trả lời về trạng thái.
    # Riêng 401 phải đi tiếp để app làm mới token.
    lich_su: list[Mapping[str, Any]] = []
    lich_su_loi = False
    try:
        lich_su, _ = _trang(await client.get(
            ServiceName.LOAN, f"/api/v1/loan-applications/{ma}/history", {"page": 0, "size": 20}
        ))
    except ToolError as exc:
        if exc.code == "PHIEN_HET_HAN":
            raise
        lich_su_loi = True

    trang_thai = dich_trang_thai("application", _chuoi(ho_so.get("status")))
    de_nghi = _so(ho_so.get("requestedAmount"))
    facts: dict[str, Any] = {
        "ma_ho_so": ma,
        "trang_thai": trang_thai,
        "so_tien_de_nghi": tien_vnd(de_nghi),
    }
    the: list[tuple[str, str | None]] = [
        ("Trạng thái", trang_thai),
        ("Số tiền đề nghị", tien_vnd(de_nghi)),
        ("Kỳ hạn", _ky_han(ho_so.get("requestedTermMonths"))),
    ]

    # Lãi cơ sở là lãi của sản phẩm lúc nộp (productSnapshot); lãi cuối có sau khi chấm điểm và định giá.
    san_pham = _con(ho_so, "productSnapshot") or {}
    co_so = _so(san_pham.get("annualInterestRate"))
    cuoi = _so(ho_so.get("finalAnnualInterestRate"))
    dieu_chinh = _so(ho_so.get("pricingAdjustmentPercentagePoints"))
    hang = _chuoi(ho_so.get("pricingCreditGrade"))
    lai: dict[str, str] = {"co_so": f"{phan_tram(co_so)}/năm" if co_so is not None else "—"}
    if cuoi is not None:
        lai["cuoi"] = f"{phan_tram(cuoi)}/năm"
    if hang:
        lai["hang_gia"] = hang
    if dieu_chinh is not None:
        lai["dieu_chinh"] = _diem_phan_tram(dieu_chinh)
    facts["lai_suat"] = lai
    the += [
        ("Lãi suất cơ sở", lai["co_so"]),
        ("Lãi suất cuối", lai.get("cuoi")),
        ("Hạng giá", lai.get("hang_gia")),
        ("Mức điều chỉnh", lai.get("dieu_chinh")),
    ]

    nguon = _chuoi(ho_so.get("decisionSource"))
    if nguon:
        facts["nguon_quyet_dinh"] = _NGUON_QUYET_DINH.get(nguon, f"{nguon} (chưa có diễn giải)")
        the.append(("Nguồn quyết định", facts["nguon_quyet_dinh"]))

    dieu_khoan = _con(ho_so, "termsConfirmation")
    if dieu_khoan:
        xac_nhan = {"trang_thai": dich_trang_thai("terms", _chuoi(dieu_khoan.get("status")))}
        if dieu_khoan.get("expiresAt"):
            xac_nhan["han"] = ngay_gio_vn(dieu_khoan.get("expiresAt"))
        facts["xac_nhan_dieu_khoan"] = xac_nhan
        han = f" · hạn {xac_nhan['han']}" if "han" in xac_nhan and dieu_khoan.get("status") == "PENDING" else ""
        the.append(("Xác nhận điều khoản", xac_nhan["trang_thai"] + han))

    cards: list[Card] = []
    goi_von = _con(ho_so, "funding")
    if goi_von:
        da_goi = _so(goi_von.get("fundedAmount")) or Decimal(0)
        thong_tin = {
            "trang_thai": dich_trang_thai("funding", _chuoi(goi_von.get("status"))),
            "da_goi": tien_vnd(da_goi),
            "can_goi": tien_vnd(de_nghi),
        }
        if goi_von.get("expiredAt"):
            thong_tin["het_han_luc"] = ngay_gio_vn(goi_von.get("expiredAt"))
        facts["goi_von"] = thong_tin
        the.append(("Gọi vốn", thong_tin["trang_thai"]))
        if de_nghi and de_nghi > 0:
            cards.append(Card(
                kind="progress",
                title="Tiến độ gọi vốn",
                value=float(da_goi),
                max=float(de_nghi),
                label=f"Đã gọi {thong_tin['da_goi']} / {thong_tin['can_goi']}",
            ))

    if lich_su:
        facts["lich_su"] = [
            {
                "thoi_diem": ngay_gio_vn(muc.get("createdAt")),
                "trang_thai_moi": dich_trang_thai("application", _chuoi(muc.get("toStatus"))),
                "ly_do": dich_ly_do(_chuoi(muc.get("reasonCode"))),
            }
            for muc in lich_su[:_LICH_SU_GUI_LLM]
        ]
    elif lich_su_loi:
        facts["lich_su"] = "Chưa tải được lịch sử xử lý."

    ly_do = _ly_do_tu_choi(lich_su) if ho_so.get("status") == "REJECTED" else []
    if ly_do:
        the.append(("Lý do", "; ".join(ly_do)))

    actions = [_hanh_dong("mo_ho_so", {"applicationNumber": ma})]
    ids = {ma}
    if ho_so.get("status") == "APPROVED":
        # Hồ sơ đã duyệt: giai đoạn thật nằm ở hợp đồng/khoản vay (xem `_giai_doan`).
        hop_dong_theo_ho_so, khoan_theo_ho_so = await _theo_ho_so(client)
        hop_dong, khoan = hop_dong_theo_ho_so.get(ma), khoan_theo_ho_so.get(ma)
        gd = _giai_doan(ho_so, hop_dong, khoan)
        if gd:
            facts["giai_doan"] = gd
            the.insert(1, ("Giai đoạn", gd["nhan"]))
        if hop_dong is not None:
            ma_hd, tt_hd = str(_chuoi(hop_dong.get("contractNumber"))), _chuoi(hop_dong.get("status"))
            facts["hop_dong"] = {"ma_hop_dong": ma_hd, "trang_thai": dich_trang_thai("contract", tt_hd)}
            the.append(("Hợp đồng", f"{ma_hd} · {facts['hop_dong']['trang_thai']}"))
            ids.add(ma_hd)
            if tt_hd in _HD_CHO_BAN_KY:
                actions.append(_hanh_dong("mo_hop_dong", {"contractNumber": ma_hd}, "Mở hợp đồng cần ký"))
        if khoan is not None:
            ma_kv = str(_chuoi(khoan.get("loanNumber")))
            facts["khoan_vay"] = {"ma_khoan_vay": ma_kv, "trang_thai": dich_trang_thai("loan", _chuoi(khoan.get("status")))}
            the.append(("Khoản vay", f"{ma_kv} · {facts['khoan_vay']['trang_thai']}"))
            ids.add(ma_kv)
            actions.append(_hanh_dong("mo_khoan_vay", {"loanNumber": ma_kv}))

    cards.insert(0, _the_kv(f"Hồ sơ {ma}", the))
    return ToolResult(facts=facts, cards=cards, actions=actions, ids=frozenset(ids))


# ---------------------------------------------------------------------------
# hop_dong_cua_toi
# ---------------------------------------------------------------------------


async def _hop_dong_cua_toi(client: FinoraApiClient, params: HopDongDangMoParams) -> ToolResult:
    payload = await client.get(ServiceName.LOAN, "/api/v1/loan-contracts/me", {"page": 0, "size": 10})
    items, tong = _trang(payload)
    dang_mo = params.contract_number
    if dang_mo and any(_chuoi(item.get("contractNumber")) == dang_mo for item in items):
        # Hợp đồng đang mở trên màn lên đầu (sort ổn định giữ thứ tự mới nhất của các hợp đồng còn lại).
        items = sorted(items, key=lambda item: 0 if _chuoi(item.get("contractNumber")) == dang_mo else 1)
    else:
        dang_mo = None
    hop_dong: list[dict[str, Any]] = []
    cho_ban_ky: list[str] = []
    for item in items:
        ma = _chuoi(item.get("contractNumber"))
        if ma is None:
            continue
        ma_trang_thai = _chuoi(item.get("status"))
        muc: dict[str, Any] = {
            "ma_hop_dong": ma,
            "trang_thai": dich_trang_thai("contract", ma_trang_thai),
            "so_tien_goc": tien_vnd(_so(item.get("principalAmount"))),
            "ky_han": _ky_han(item.get("termMonths")),
            "lai_suat": f"{phan_tram(_so(item.get('annualInterestRate')))}/năm",
        }
        # Hạn ký chỉ còn ý nghĩa khi hợp đồng đang trong vòng ký; hợp đồng đã ký/hết hạn thì bỏ.
        if ma_trang_thai in _HD_DANG_KY and item.get("expiresAt"):
            muc["han_ky"] = ngay_gio_vn(item.get("expiresAt"))
        if ma_trang_thai in _HD_CHO_BAN_KY:
            cho_ban_ky.append(ma)
        hop_dong.append(muc)
    if not hop_dong:
        return ToolResult(facts={"tong_so_hop_dong": 0, "hop_dong": [], "ghi_chu": KHONG_CO_HOP_DONG})

    the = Card(
        kind="table",
        title="Hợp đồng vay của tôi",
        columns=["Mã hợp đồng", "Trạng thái", "Số tiền gốc", "Hạn ký"],
        rows=[[h["ma_hop_dong"], h["trang_thai"], h["so_tien_goc"], h.get("han_ky", "—")] for h in hop_dong],
    )
    if cho_ban_ky:
        hanh_dong = _hanh_dong("mo_hop_dong", {"contractNumber": cho_ban_ky[0]}, "Mở hợp đồng cần ký")
    else:
        hanh_dong = _hanh_dong("mo_hop_dong", {"contractNumber": hop_dong[0]["ma_hop_dong"]})
    facts: dict[str, Any] = {"tong_so_hop_dong": tong, "so_hop_dong_cho_ban_ky": len(cho_ban_ky), "hop_dong": hop_dong}
    if dang_mo:
        facts["hop_dong_dang_mo"] = dang_mo
    return ToolResult(
        facts=facts,
        cards=[the],
        actions=[hanh_dong],
        ids=frozenset(h["ma_hop_dong"] for h in hop_dong),
    )


# ---------------------------------------------------------------------------
# khoan_vay_cua_toi
# ---------------------------------------------------------------------------


def _khoa_sap_xep_khoan(item: Mapping[str, Any]) -> tuple[int, str]:
    """Khoản đang trả có kỳ tới gần nhất lên đầu; khoản đã tất toán/không có lịch xuống cuối."""
    ngay = _chuoi(item.get("nextDueDate"))
    if item.get("status") in _KHOAN_DANG_TRA and ngay:
        return 0, ngay  # ngày ISO yyyy-mm-dd so chuỗi đúng thứ tự thời gian
    return 1, ""


def _ngay_iso(value: Any) -> str | None:
    """"2026-11-11" (LocalDate của Loan) → chuỗi so được theo thứ tự thời gian; sai khuôn → None."""
    chuoi = _chuoi(value)
    return chuoi[:10] if chuoi and re.fullmatch(r"\d{4}-\d{2}-\d{2}", chuoi[:10]) else None


def _khoan_cua_toi(items: Sequence[Mapping[str, Any]], ma: str | None) -> Mapping[str, Any] | None:
    """Khoản có mã `ma` trong danh sách của chính người hỏi; mã của người khác (context giả) → None."""
    return next((item for item in items if ma and _chuoi(item.get("loanNumber")) == ma), None)


async def _khoan_vay_cua_toi(client: FinoraApiClient, params: KhoanDangMoParams) -> ToolResult:
    payload = await client.get(ServiceName.LOAN, "/api/v1/loans/me", {"page": 0, "size": 10}, slow=True)
    items, tong = _trang(payload)
    items = sorted((i for i in items if _chuoi(i.get("loanNumber"))), key=_khoa_sap_xep_khoan)
    if not items:
        return ToolResult(facts={"tong_so_khoan_vay": 0, "khoan_vay": [], "ghi_chu": KHONG_CO_KHOAN_VAY})
    # Khoản đang mở trên màn chi tiết lên đầu: câu hỏi "khoản này" là về khoản đó, không phải khoản gấp nhất.
    dang_mo = params.loan_number if _khoan_cua_toi(items, params.loan_number) is not None else None
    if dang_mo:
        items = sorted(items, key=lambda item: 0 if _chuoi(item.get("loanNumber")) == dang_mo else 1)
    hom_nay = _hom_nay_vn().isoformat()

    khoan_vay: list[dict[str, Any]] = []
    hang: list[list[str]] = []
    cu_nhat: str | None = None
    for item in items:
        ma = str(_chuoi(item.get("loanNumber")))
        muc: dict[str, Any] = {
            "ma_khoan_vay": ma,
            "trang_thai": dich_trang_thai("loan", _chuoi(item.get("status"))),
            "cap_nhat_luc": ngay_gio_vn(item.get("dataAsOf")),
            "du_lieu_cu": bool(item.get("stale")),
        }
        ky_toi = "—"
        ngay_ky = _ngay_iso(item.get("nextDueDate"))
        if item.get("status") in _KHOAN_DANG_TRA and ngay_ky:
            ky = {"ngay": ngay_vn(item.get("nextDueDate")), "so_tien": tien_vnd(_so(item.get("nextDueAmount")))}
            if ngay_ky >= hom_nay:
                muc["ky_toi"] = ky
                ky_toi = f"{ky['ngay']} · {ky['so_tien']}"
            else:
                # Khoản quá hạn: nextDueDate của Loan là kỳ chưa trả sớm nhất, đã qua — không gọi là "kỳ tới".
                muc["ky_chua_tra"] = ky
                ky_toi = f"{ky['ngay']} · {ky['so_tien']} (đã quá hạn)"
        qua_han_tien = _so(item.get("overdueAmount")) or Decimal(0)
        dpd = _so_nguyen(item.get("daysPastDue")) or 0
        qua_han = "Không"
        if qua_han_tien > 0 or dpd > 0:
            # Nhóm nợ ở đây do FINORA suy ra từ số ngày quá hạn, không phải kết quả phân loại của CIC.
            muc["qua_han"] = {"so_tien": tien_vnd(qua_han_tien), "so_ngay": dpd, "nhom_no": _nhom_no(dpd)}
            qua_han = f"{muc['qua_han']['so_tien']} ({dpd} ngày)"
        if muc["du_lieu_cu"] and cu_nhat is None:
            cu_nhat = muc["cap_nhat_luc"]
        khoan_vay.append(muc)
        # Dư nợ chỉ hiện trên thẻ: spec mục 8.1 không cho gửi trường này cho LLM.
        hang.append([ma, muc["trang_thai"], ky_toi, qua_han, tien_vnd(_so(item.get("totalOutstanding")))])

    cards = [Card(
        kind="table",
        title="Khoản vay của tôi",
        columns=["Mã khoản vay", "Trạng thái", "Kỳ tới", "Quá hạn", "Dư nợ"],
        rows=hang,
    )]
    if cu_nhat:
        cards.append(_the_kv("Số liệu có thể chưa mới", [
            ("Cập nhật lúc", cu_nhat),
            ("Lưu ý", "Có thể chưa gồm khoản bạn vừa trả; mở khoản vay để tải lại."),
        ]))

    qua_han_dau = next((k for k in khoan_vay if "qua_han" in k), None)
    co_ky_toi = next((k for k in khoan_vay if "ky_toi" in k), None)
    actions: list[Action] = []
    facts: dict[str, Any] = {"tong_so_khoan_vay": tong, "khoan_vay": khoan_vay}
    if dang_mo:
        facts["khoan_dang_mo"] = dang_mo
        dang_xem = khoan_vay[0]
        if "qua_han" in dang_xem or "ky_toi" in dang_xem or "ky_chua_tra" in dang_xem:
            nhan = "Trả khoản quá hạn" if "qua_han" in dang_xem else None
            actions.append(_hanh_dong("tra_no", {"loanNumber": dang_mo}, nhan))
    elif qua_han_dau:
        actions.append(_hanh_dong("tra_no", {"loanNumber": qua_han_dau["ma_khoan_vay"]}, "Trả khoản quá hạn"))
    elif co_ky_toi:
        actions.append(_hanh_dong("mo_khoan_vay", {"loanNumber": co_ky_toi["ma_khoan_vay"]}))
    return ToolResult(
        facts=facts,
        cards=cards,
        actions=actions,
        ids=frozenset(k["ma_khoan_vay"] for k in khoan_vay),
    )


# ---------------------------------------------------------------------------
# vi_cua_toi
# ---------------------------------------------------------------------------


async def _ky_tra_gan_nhat(client: FinoraApiClient, dang_mo: str | None = None) -> Mapping[str, Any] | None:
    """Khoản cần so với số dư: khoản đang mở trên màn (nếu là của người hỏi), không thì khoản đang trả có kỳ
    sớm nhất. Lỗi tạm thời → None (chỉ là phần bổ sung)."""
    try:
        items, _ = _trang(await client.get(ServiceName.LOAN, "/api/v1/loans/me", {"page": 0, "size": 10}))
    except ToolError as exc:
        if exc.code == "PHIEN_HET_HAN":
            raise
        return None
    khoan = _khoan_cua_toi(items, dang_mo)
    if khoan is not None and (khoan.get("status") in _KHOAN_DANG_TRA or (_so(khoan.get("overdueAmount")) or 0) > 0):
        return khoan
    dang_tra = sorted(
        (i for i in items if _khoa_sap_xep_khoan(i)[0] == 0 and _chuoi(i.get("loanNumber"))),
        key=_khoa_sap_xep_khoan,
    )
    return dang_tra[0] if dang_tra else None


async def _vi_cua_toi(client: FinoraApiClient, params: KhoanDangMoParams) -> ToolResult:
    vi = _doi_tuong(await client.get(ServiceName.PAYMENT, "/api/v1/wallets/me"))
    kha_dung = _so(vi.get("available")) or Decimal(0)
    dang_giu = _so(vi.get("held")) or Decimal(0)
    facts: dict[str, Any] = {"so_du_kha_dung": tien_vnd(kha_dung), "dang_giu": tien_vnd(dang_giu)}
    the: list[tuple[str, str | None]] = [
        ("Số dư khả dụng", facts["so_du_kha_dung"]),
        ("Đang giữ", facts["dang_giu"]),
        ("Trạng thái ví", _TRANG_THAI_VI.get(str(vi.get("status")))),
    ]
    actions: list[Action] = []
    ids: set[str] = set()

    # Ghép kỳ trả gần nhất để trả lời "ví có đủ trả kỳ tới không" trong một công cụ (gợi ý
    # borrower.wallet.enough chỉ chạy một công cụ). Trường lấy thêm nằm trong danh sách 8.1 của
    # khoan_vay_cua_toi, không mở thêm loại dữ liệu nào.
    khoan = await _ky_tra_gan_nhat(client, params.loan_number)
    if khoan is not None:
        ma = str(_chuoi(khoan.get("loanNumber")))
        qua_han = _so(khoan.get("overdueAmount")) or Decimal(0)
        if qua_han > 0:
            # Đang quá hạn: phải trả hết phần quá hạn (có thể nhiều kỳ), so với một kỳ là báo thiếu sai.
            so_ngay = _so_nguyen(khoan.get("daysPastDue")) or 0
            facts["qua_han"] = {"ma_khoan_vay": ma, "so_tien": tien_vnd(qua_han), "so_ngay": so_ngay}
            can_tra = qua_han
            the.append(("Phần quá hạn", f"{facts['qua_han']['so_tien']} ({so_ngay} ngày)"))
        else:
            can_tra = _so(khoan.get("nextDueAmount")) or Decimal(0)
            facts["ky_toi"] = {"ma_khoan_vay": ma, "ngay": ngay_vn(khoan.get("nextDueDate")),
                               "so_tien": tien_vnd(can_tra)}
            the.append(("Kỳ tới", f"{facts['ky_toi']['ngay']} · {facts['ky_toi']['so_tien']}"))
        thieu = can_tra - kha_dung
        facts["du_tra_qua_han" if qua_han > 0 else "du_tra_ky_toi"] = "có" if thieu <= 0 else "không"
        if thieu > 0:
            facts["con_thieu"] = tien_vnd(thieu)
        the.append(("Còn thiếu", facts.get("con_thieu")))
        ids.add(ma)
        if thieu <= 0:
            actions.append(_hanh_dong("tra_no", {"loanNumber": ma}))
    if not actions:
        actions.append(_hanh_dong("nap_tien"))
    return ToolResult(facts=facts, cards=[_the_kv("Ví FINORA", the)], actions=actions, ids=frozenset(ids))


# ---------------------------------------------------------------------------
# trang_thai_pin
# ---------------------------------------------------------------------------


async def _trang_thai_pin(client: FinoraApiClient, params: NoParams) -> ToolResult:
    pin = _doi_tuong(await client.get(ServiceName.USER, "/api/v1/users/me/pin"))
    da_dat = pin.get("hasPin") is True
    bi_khoa = pin.get("locked") is True
    facts: dict[str, Any] = {
        "da_dat_pin": "Đã tạo mã PIN" if da_dat else "Chưa tạo mã PIN",
        "bi_khoa": "Đang bị khóa" if bi_khoa else "Không bị khóa",
    }
    if bi_khoa and pin.get("lockedUntil"):
        facts["khoa_den"] = ngay_gio_vn(pin.get("lockedUntil"))
    con_lai = _so_nguyen(pin.get("remainingAttempts"))
    the = _the_kv("Mã PIN giao dịch", [
        ("Trạng thái", facts["da_dat_pin"] if not da_dat else facts["bi_khoa"]),
        ("Khóa đến", facts.get("khoa_den")),
        # Số lần còn lại chỉ hiện trên thẻ (spec 8.1 chỉ cho gửi "đã đặt chưa, khóa đến khi nào").
        ("Số lần nhập sai còn lại", str(con_lai) if da_dat and not bi_khoa and con_lai is not None else None),
    ])
    return ToolResult(facts=facts, cards=[the])


# ---------------------------------------------------------------------------
# san_pham_vay (PUBLIC)
# ---------------------------------------------------------------------------


async def _san_pham_vay(client: FinoraApiClient, params: NoParams) -> ToolResult:
    payload = await client.get(ServiceName.LOAN, "/api/v1/loan-products", {"page": 0, "size": 20})
    items, tong = _trang(payload)
    san_pham: list[dict[str, Any]] = []
    for item in items:
        ma = _so_nguyen(item.get("id"))
        ten = _chuoi(item.get("name"))
        if ma is None or ten is None:
            continue
        san_pham.append({
            "id": ma,
            "ten": ten[:80],
            "so_tien": f"{tien_vnd(_so(item.get('minAmount')))} – {tien_vnd(_so(item.get('maxAmount')))}",
            "ky_han": _khoang_ky_han(item.get("minTermMonths"), item.get("maxTermMonths")),
            "lai_suat_nam": {
                "thap_nhat": phan_tram(_so(item.get("minAnnualInterestRate"))),
                "co_so": phan_tram(_so(item.get("annualInterestRate"))),
                "cao_nhat": phan_tram(_so(item.get("maxAnnualInterestRate"))),
            },
            "cach_tra": _CACH_TRA.get(str(item.get("repaymentMethod")), "—"),
        })
    if not san_pham:
        return ToolResult(facts={"so_san_pham": 0, "san_pham": [], "ghi_chu": KHONG_CO_SAN_PHAM})

    the = Card(
        kind="table",
        title="Sản phẩm vay đang mở bán",
        columns=["Sản phẩm", "Số tiền", "Kỳ hạn", "Lãi suất/năm"],
        rows=[
            [
                s["ten"],
                s["so_tien"],
                s["ky_han"],
                _khoang_lai(s["lai_suat_nam"]),
            ]
            for s in san_pham
        ],
    )
    return ToolResult(
        facts={"so_san_pham": tong, "san_pham": san_pham},
        cards=[the],
        actions=[_hanh_dong("xem_san_pham", {"productId": str(s["id"])}, f"Xem {s['ten'][:30]}") for s in san_pham[:2]],
        ids=frozenset(str(s["id"]) for s in san_pham),
    )


# ---------------------------------------------------------------------------
# mo_phong_khoan_vay (PUBLIC, chậm vì Loan gọi Fineract)
# ---------------------------------------------------------------------------


async def _mo_phong_khoan_vay(client: FinoraApiClient, params: MoPhongKhoanVayParams) -> ToolResult:
    ngay_giai_ngan = _hom_nay_vn() + timedelta(days=_SO_NGAY_GIAI_NGAN_GIA_DINH)
    body = {
        "amount": params.amount,
        "termMonths": params.term_months,
        "expectedDisbursementDate": ngay_giai_ngan.isoformat(),
    }
    try:
        payload = await client.post(
            ServiceName.LOAN, f"/api/v1/loan-products/{params.product_id}/repayment-previews", body, slow=True
        )
    except ToolError as exc:
        # Loan trả 400 LOAN_TERMS_OUT_OF_RANGE / 409 LOAN_PRODUCT_NOT_AVAILABLE (relay gom thành
        # DU_LIEU_KHONG_HOP_LE) và 503 CORE_LENDING_UNAVAILABLE khi Fineract chưa sẵn (DICH_VU_LOI).
        if exc.code == "DU_LIEU_KHONG_HOP_LE":
            raise ToolError(exc.code, (
                "FINORA không tính được lịch cho lựa chọn này: số tiền hoặc kỳ hạn có thể nằm ngoài giới hạn "
                "của sản phẩm, hoặc sản phẩm đang tạm ngừng. Bạn xem lại giới hạn trong danh sách sản phẩm vay."
            )) from exc
        if exc.code == "DICH_VU_LOI":
            raise ToolError(exc.code, "Hệ thống đang chuẩn bị lịch trả nợ, bạn thử lại sau khoảng 30 giây.") from exc
        raise
    lich = _doi_tuong(payload)
    facts: dict[str, Any] = {
        "so_tien_vay": tien_vnd(params.amount),
        "ky_han": f"{params.term_months} tháng",
        "ky_dau": tien_vnd(_so(lich.get("firstInstallment"))),
        "ky_cao_nhat": tien_vnd(_so(lich.get("maximumInstallment"))),
        "tong_lai": tien_vnd(_so(lich.get("totalInterest"))),
        "tong_phi": tien_vnd(_so(lich.get("totalFees"))),
        "tong_phai_tra": tien_vnd(_so(lich.get("totalRepayment"))),
        "ghi_chu": (
            f"Tạm tính theo lãi suất cơ sở của sản phẩm, giả định giải ngân ngày {ngay_vn(ngay_giai_ngan)}; "
            "lãi suất cuối của hồ sơ phụ thuộc kết quả chấm điểm."
        ),
    }
    the = _the_kv("Tạm tính khoản vay", [
        ("Số tiền vay", facts["so_tien_vay"]),
        ("Kỳ hạn", facts["ky_han"]),
        ("Lãi suất tạm tính", f"{phan_tram(_so(lich.get('annualInterestRate')))}/năm"),
        ("Cách trả", _CACH_TRA.get(str(lich.get("repaymentMethod")))),
        ("Kỳ đầu", facts["ky_dau"]),
        ("Kỳ cao nhất", facts["ky_cao_nhat"]),
        ("Tổng lãi", facts["tong_lai"]),
        ("Tổng phí", facts["tong_phi"]),
        ("Tổng phải trả", facts["tong_phai_tra"]),
        ("Ngày giải ngân giả định", ngay_vn(lich.get("estimatedDisbursementDate") or ngay_giai_ngan)),
    ])
    return ToolResult(
        facts=facts,
        cards=[the],
        actions=[_hanh_dong("xem_san_pham", {"productId": str(params.product_id)}, "Xem sản phẩm và nộp hồ sơ")],
        ids=frozenset({str(params.product_id)}),
    )


# ---------------------------------------------------------------------------
# goi_y_khoan_vay (phụ lục, Hải duyệt 2026-10-16)
# ---------------------------------------------------------------------------

_GHI_CHU_GOI_Y = (
    (
        "Lãi suất là lãi suất cơ sở; lãi suất cuối có thể tăng hoặc giảm sau khi chấm điểm tín dụng, "
        "nếu bất lợi hơn bạn sẽ được hỏi xác nhận lại."
    ),
    "Đây không phải cam kết duyệt hồ sơ.",
)
_CS_LAI_CO_SO = Citation(
    id="CS-LAI-CO-SO",
    kind="CHINH_SACH",
    label="Thông báo lãi suất cơ sở của FINORA",
    url=None,
    checked_at="2026-10-16",
)


def _goi_khop(item: Mapping[str, Any], amount: int, term_months: int) -> bool:
    min_tien, max_tien = _so(item.get("minAmount")), _so(item.get("maxAmount"))
    thap_ky, cao_ky = _so_nguyen(item.get("minTermMonths")), _so_nguyen(item.get("maxTermMonths"))
    if min_tien is None or max_tien is None or thap_ky is None or cao_ky is None:
        return False
    return min_tien <= Decimal(amount) <= max_tien and thap_ky <= term_months <= cao_ky


async def _goi_y_khoan_vay(client: FinoraApiClient, params: GoiYKhoanVayParams) -> ToolResult:
    payload = await client.get(ServiceName.LOAN, "/api/v1/loan-products", {"page": 0, "size": 20})
    items, _ = _trang(payload)

    hop_le = [item for item in items if _so_nguyen(item.get("id")) is not None and _chuoi(item.get("name"))]
    khop = [item for item in hop_le if _goi_khop(item, params.amount, params.term_months)]

    so_tien_fmt = tien_vnd(params.amount)
    ky_han_fmt = f"{params.term_months} tháng"

    if not khop:
        khoang = [
            {
                "ten": str(item.get("name"))[:80],
                "khoang_tien": f"{tien_vnd(_so(item.get('minAmount')))} – {tien_vnd(_so(item.get('maxAmount')))}",
                "khoang_ky_han": _khoang_ky_han(item.get("minTermMonths"), item.get("maxTermMonths")),
            }
            for item in hop_le
        ]
        ghi_chu = (
            f"Không có gói vay nào nhận số tiền {so_tien_fmt} với kỳ hạn {ky_han_fmt}. "
            "Bạn xem khoảng số tiền/kỳ hạn của các gói đang mở bán ở bảng bên dưới."
        )
        the = Card(
            kind="table",
            title="Không có gói vay phù hợp",
            columns=["Sản phẩm", "Khoảng số tiền", "Khoảng kỳ hạn"],
            rows=[[k["ten"], k["khoang_tien"], k["khoang_ky_han"]] for k in khoang],
        )
        return ToolResult(
            facts={"khop": False, "ghi_chu": ghi_chu, "khoang_cac_goi": khoang},
            cards=[the] if khoang else [],
        )

    # Ưu tiên lãi suất cơ sở thấp hơn trước khi lấy tối đa 3 gói để gọi repayment-previews.
    khop.sort(key=lambda item: _so(item.get("annualInterestRate")) or Decimal(999))
    chon = khop[:3]

    ngay_giai_ngan = _hom_nay_vn() + timedelta(days=_SO_NGAY_GIAI_NGAN_GOI_Y)
    body = {
        "amount": params.amount,
        "termMonths": params.term_months,
        "expectedDisbursementDate": ngay_giai_ngan.isoformat(),
    }

    async def _preview(item: Mapping[str, Any]) -> tuple[Mapping[str, Any], Mapping[str, Any] | None]:
        product_id = _so_nguyen(item.get("id"))
        try:
            payload = await client.post(
                ServiceName.LOAN, f"/api/v1/loan-products/{product_id}/repayment-previews", body, slow=True
            )
        except ToolError:
            return item, None
        return item, _doi_tuong(payload)

    ket_qua = await asyncio.gather(*(_preview(item) for item in chon))

    thanh_cong = [(item, lich) for item, lich in ket_qua if lich is not None]
    goi_loi = [str(item.get("name"))[:80] for item, lich in ket_qua if lich is None]

    if not thanh_cong:
        raise ToolError("DICH_VU_LOI", "Hệ thống đang chuẩn bị lịch trả nợ cho các gói vay, bạn thử lại sau ít phút.")

    nguong, la_mac_dinh = _nguong_ty_le_tra_no()
    co_thu_nhap = params.monthly_income is not None and params.monthly_income > 0

    goi_facts: list[dict[str, Any]] = []
    hang: list[list[str]] = []
    ids: set[str] = set()
    for item, lich in thanh_cong:
        product_id = _so_nguyen(item.get("id"))
        ten = str(item.get("name"))[:80]
        ids.add(str(product_id))
        ky_cao_nhat = _so(lich.get("maximumInstallment")) or Decimal(0)
        muc: dict[str, Any] = {
            "ten": ten,
            "ma_goi": str(product_id),
            "lai_co_so": f"{phan_tram(_so(lich.get('annualInterestRate')))}/năm",
            "ky_dau": tien_vnd(_so(lich.get("firstInstallment"))),
            "ky_cao_nhat": tien_vnd(ky_cao_nhat),
            "tong_lai": tien_vnd(_so(lich.get("totalInterest"))),
            "tong_phi": tien_vnd(_so(lich.get("totalFees"))),
            "tong_phai_tra": tien_vnd(_so(lich.get("totalRepayment"))),
        }
        hang_dong = [ten, muc["lai_co_so"], muc["ky_cao_nhat"], muc["tong_lai"], muc["tong_phai_tra"]]
        if co_thu_nhap:
            no_khac = Decimal(params.monthly_debt or 0)
            thu_nhap = Decimal(params.monthly_income)
            ty_le = (ky_cao_nhat + no_khac) / thu_nhap
            muc["ty_le_tra_no"] = phan_tram(ty_le * 100)
            muc["vuot_nguong"] = ty_le > nguong
            hang_dong.append(muc["ty_le_tra_no"] + (" (cao)" if muc["vuot_nguong"] else ""))
        goi_facts.append(muc)
        hang.append(hang_dong)

    # Xếp hạng bằng số thật (Decimal) trước khi định dạng, tránh so sánh chuỗi tiền tệ sai thứ tự;
    # hòa thì gói lãi cơ sở thấp hơn đứng trước (đã sort theo lãi cơ sở ở bước chọn 3 gói).
    truong_xep = "totalInterest" if params.tieu_chi == "TONG_LAI" else "maximumInstallment"

    def _khoa_xep(cap: tuple[Mapping[str, Any], Mapping[str, Any]]) -> Decimal:
        return _so(cap[1].get(truong_xep)) or Decimal(999999999999)

    thu_tu = sorted(range(len(thanh_cong)), key=lambda i: _khoa_xep(thanh_cong[i]))
    goi_facts = [goi_facts[i] for i in thu_tu]
    hang = [hang[i] for i in thu_tu]

    facts: dict[str, Any] = {
        "tieu_chi": params.tieu_chi,
        "so_tien": so_tien_fmt,
        "ky_han": ky_han_fmt,
        "goi": goi_facts,
        "ghi_chu": list(_GHI_CHU_GOI_Y),
    }
    if la_mac_dinh and co_thu_nhap:
        facts["ghi_chu"] = [
            *facts["ghi_chu"],
            "Không đọc được ngưỡng cảnh báo tỷ lệ trả nợ từ cấu hình, đã dùng mốc mặc định 50%.",
        ]
    if goi_loi:
        facts["goi_loi"] = goi_loi

    cot = ["Gói", "Lãi cơ sở", "Trả/tháng (cao nhất)", "Tổng lãi", "Tổng phải trả"]
    if co_thu_nhap:
        cot.append("Tỷ lệ trả nợ")
    the = Card(kind="table", title="So sánh gói vay", columns=cot, rows=hang)

    hang_1 = goi_facts[0]
    actions: list[Action] = [
        _hanh_dong("nop_ho_so", {
            "productId": hang_1["ma_goi"],
            "amount": str(params.amount),
            "termMonths": str(params.term_months),
            "expectedDisbursementDate": ngay_giai_ngan.isoformat(),
        }),
        _hanh_dong("xem_san_pham", {"productId": hang_1["ma_goi"]}),
    ]

    return ToolResult(
        facts=facts,
        cards=[the],
        actions=actions,
        citations=[_CS_LAI_CO_SO],
        ids=frozenset(ids),
    )


# ---------------------------------------------------------------------------
# Câu mẫu cho nút gợi ý (đường A: không gọi LLM)
# ---------------------------------------------------------------------------


def _buoc_tiep_theo(nhan: Any) -> str | None:
    for ma, cau in _BUOC_TIEP_THEO_HO_SO.items():
        if _la("application", nhan, ma):
            return cau
    return None


def _render_trang_thai_ho_so(result: ToolResult) -> str:
    facts = result.facts
    ho_so = facts.get("ho_so") or []
    if not ho_so:
        return f"{KHONG_CO_HO_SO} Bạn có thể xem các sản phẩm vay ở tab Sàn và nộp hồ sơ ngay trên app."
    moi = ho_so[0]
    tong = facts.get("tong_so_ho_so", len(ho_so))
    return _cau(
        f"Hồ sơ mới nhất của bạn là {moi['ma_ho_so']}, nộp ngày {moi['ngay_nop']}, đề nghị vay "
        f"{moi['so_tien_de_nghi']}, hiện ở trạng thái “{moi['trang_thai']}”.",
        moi.get("giai_thich") or _buoc_tiep_theo(moi["trang_thai"]),
        f"Bạn có tổng cộng {so_nguyen(tong)} hồ sơ; bảng bên dưới liệt kê các hồ sơ gần nhất." if tong > 1 else None,
    )


def _trang_thai_va_buoc_tiep(facts: Mapping[str, Any]) -> tuple[str, str | None]:
    """Nhãn hiển thị + câu "nghĩa là gì, làm gì tiếp" của MỘT hồ sơ: theo giai đoạn nếu đã duyệt (khớp app)."""
    gd = facts.get("giai_doan")
    if gd:
        return gd["nhan"], _cau_giai_doan(gd)
    return facts["trang_thai"], _buoc_tiep_theo(facts["trang_thai"])


def _render_trang_thai_mot_ho_so(result: ToolResult) -> str:
    """Câu mẫu "Hồ sơ này đang ở bước nào?" (fix-2): trạng thái/giai đoạn của đúng hồ sơ đang hỏi."""
    facts = result.facts
    nhan, tiep = _trang_thai_va_buoc_tiep(facts)
    ly_do = _gia_tri_the(result, "Lý do") if _la("application", facts["trang_thai"], "REJECTED") else None
    return _cau(
        f"Hồ sơ {facts['ma_ho_so']} (đề nghị vay {facts['so_tien_de_nghi']}) đang ở trạng thái “{nhan}”.",
        f"Lý do ghi nhận: {ly_do}." if ly_do else None,
        tiep,
    )


def _render_ly_do_tu_choi(result: ToolResult) -> str:
    facts = result.facts
    ma, trang_thai = facts["ma_ho_so"], facts["trang_thai"]
    if not _la("application", trang_thai, "REJECTED"):
        nhan, tiep = _trang_thai_va_buoc_tiep(facts)
        return _cau(f"Hồ sơ {ma} không bị từ chối: trạng thái hiện tại là “{nhan}”.", tiep)
    ly_do = _gia_tri_the(result, "Lý do")
    nguon = facts.get("nguon_quyet_dinh")
    return _cau(
        f"Hồ sơ {ma} không được duyệt.",
        f"Lý do ghi nhận: {ly_do}." if ly_do else "FINORA chưa ghi lý do cụ thể cho quyết định này.",
        _CAU_NGUON_QUYET_DINH.get(nguon) if isinstance(nguon, str) else None,
        "Bạn có thể nộp hồ sơ mới khi điều kiện thay đổi; hồ sơ mới được kiểm tra và chấm điểm lại từ đầu.",
    )


def _render_lai_suat_thay_doi(result: ToolResult) -> str:
    facts = result.facts
    ma = facts["ma_ho_so"]
    lai = facts.get("lai_suat") or {}
    co_so, cuoi = lai.get("co_so", "—"), lai.get("cuoi")
    if not cuoi:
        return (
            f"Hồ sơ {ma} chưa có lãi suất cuối vì chưa chấm điểm xong. Con số bạn thấy lúc nộp là lãi suất cơ sở "
            f"{co_so} của sản phẩm; lãi suất cuối có thể khác sau khi chấm điểm."
        )
    if cuoi == co_so:
        return f"Lãi suất cuối của hồ sơ {ma} là {cuoi}, bằng lãi suất cơ sở của sản phẩm, nên không có thay đổi."
    dieu_khoan = facts.get("xac_nhan_dieu_khoan") or {}
    can_xac_nhan = _la("terms", dieu_khoan.get("trang_thai"), "PENDING")
    return _cau(
        f"Lúc nộp, hồ sơ {ma} được tính theo lãi suất cơ sở {co_so} của sản phẩm.",
        (
            f"Sau khi chấm điểm, hồ sơ được xếp hạng giá {lai['hang_gia']} nên lãi suất được điều chỉnh "
            f"{lai['dieu_chinh']}, thành {cuoi}."
            if lai.get("hang_gia") and lai.get("dieu_chinh")
            else f"Sau khi chấm điểm, lãi suất cuối là {cuoi}."
        ),
        "Đây là cách FINORA định giá theo mức rủi ro của từng hồ sơ.",
        (
            f"Vì điều khoản cuối khác lúc nộp, bạn cần mở hồ sơ để đồng ý hoặc từ chối trước {dieu_khoan['han']}."
            if can_xac_nhan and dieu_khoan.get("han")
            else None
        ),
    )


def _render_chua_giai_ngan(result: ToolResult) -> str:
    facts = result.facts
    ma, trang_thai = facts["ma_ho_so"], facts["trang_thai"]
    if not _la("application", trang_thai, "APPROVED"):
        return _cau(
            f"Hồ sơ {ma} đang ở trạng thái “{trang_thai}”, chưa được duyệt nên chưa tới bước giải ngân.",
            _buoc_tiep_theo(trang_thai),
        )
    khoan = facts.get("khoan_vay")
    if khoan:
        return _cau(
            f"Hồ sơ {ma} đã được giải ngân thành khoản vay {khoan['ma_khoan_vay']} ({khoan['trang_thai']}).",
            "Lịch trả nợ và kỳ tới xem ở mục Khoản vay.",
        )
    dieu_khoan = facts.get("xac_nhan_dieu_khoan") or {}
    if _la("terms", dieu_khoan.get("trang_thai"), "PENDING"):
        han = f" trước {dieu_khoan['han']}" if dieu_khoan.get("han") else ""
        return (
            f"Hồ sơ {ma} đã được duyệt nhưng điều khoản cuối khác lúc nộp, nên bạn cần mở hồ sơ để xác nhận{han}. "
            "Sau khi bạn đồng ý, hồ sơ mới được đưa lên sàn gọi vốn; tiền chỉ được giải ngân sau khi gọi đủ vốn và "
            "hai bên ký hợp đồng."
        )
    if _la("terms", dieu_khoan.get("trang_thai"), "DECLINED", "EXPIRED"):
        gd = facts.get("giai_doan") or {}
        return _cau(f"Hồ sơ {ma} sẽ không được giải ngân.", _cau_giai_doan(gd) if gd else None)
    hop_dong = facts.get("hop_dong")
    if hop_dong:
        gd = facts.get("giai_doan") or {}
        return _cau(
            f"Hồ sơ {ma} đã gọi đủ vốn và có hợp đồng {hop_dong['ma_hop_dong']} đang ở trạng thái "
            f"“{hop_dong['trang_thai']}”.",
            _cau_giai_doan(gd) if gd else None,
            "Tiền chỉ được giải ngân sau khi hợp đồng có hiệu lực.",
        )
    goi_von = facts.get("goi_von")
    mo_dau = f"Hồ sơ {ma} đã được duyệt, nghĩa là được mời ký và gọi vốn, chưa phải giải ngân."
    if not goi_von:
        return _cau(mo_dau, "Hồ sơ đang chờ được đưa lên sàn gọi vốn.")
    if _la("funding", goi_von.get("trang_thai"), "FULLY_FUNDED"):
        return _cau(
            f"Hồ sơ {ma} đã gọi đủ {goi_von['can_goi']}.",
            "Khoản vay đã đủ vốn và FINORA đang lập hợp đồng chung cho các bên: các nhà đầu tư ký trước, sau đó "
            "tới lượt bạn. Tiền được giải ngân sau khi hợp đồng có hiệu lực.",
        )
    if _la("funding", goi_von.get("trang_thai"), "EXPIRED"):
        het_han = f" lúc {goi_von['het_han_luc']}" if goi_von.get("het_han_luc") else ""
        return _cau(
            mo_dau,
            f"Tuy nhiên đợt gọi vốn đã hết hạn{het_han} khi mới gọi được {goi_von['da_goi']} trên "
            f"{goi_von['can_goi']}, nên đợt này không lập hợp đồng và không giải ngân.",
        )
    return _cau(
        mo_dau,
        f"Hiện đã gọi được {goi_von['da_goi']} trên {goi_von['can_goi']}; khi đủ vốn, nhà đầu tư và bạn ký hợp "
        "đồng rồi tiền mới được chuyển.",
    )


def _render_hop_dong_can_ky(result: ToolResult) -> str:
    facts = result.facts
    hop_dong = facts.get("hop_dong") or []
    if not hop_dong:
        return f"{KHONG_CO_HOP_DONG} Hợp đồng chỉ được lập sau khi hồ sơ được duyệt và gọi đủ vốn."
    cho_ky = [h for h in hop_dong if _la("contract", h["trang_thai"], *_HD_CHO_BAN_KY)]
    dang_mo = next((h for h in hop_dong if h["ma_hop_dong"] == facts.get("hop_dong_dang_mo")), None)
    if dang_mo is not None:
        # Màn chi tiết hợp đồng: trả lời về đúng hợp đồng đang mở trước (fix-2).
        ma_tt = next((m for m in (*_HD_DANG_KY, "SIGNED", "DECLINED", "EXPIRED", "EFFECTIVE", "COMPLETED")
                      if _la("contract", dang_mo["trang_thai"], m)), None)
        han = f" Hạn ký: {dang_mo['han_ky']}." if dang_mo.get("han_ky") else ""
        khac = [h for h in cho_ky if h is not dang_mo]
        return _cau(
            f"Hợp đồng {dang_mo['ma_hop_dong']} đang ở trạng thái “{dang_mo['trang_thai']}”.",
            giai_thich_trang_thai("contract", ma_tt),
            _BUOC_THEO_HOP_DONG.get(str(ma_tt)),
            han.strip() or None,
            f"Bạn còn {len(khac)} hợp đồng khác đang chờ bạn ký." if khac else None,
        )
    if cho_ky:
        dau = cho_ky[0]
        han = f" (hạn ký {dau['han_ky']})" if dau.get("han_ky") else ""
        them = f" và {len(cho_ky) - 1} hợp đồng khác" if len(cho_ky) > 1 else ""
        return (
            f"Bạn có hợp đồng {dau['ma_hop_dong']}{han}{them} đang chờ bạn ký. Mở hợp đồng để đọc bản PDF rồi xác "
            "nhận bằng mã PIN giao dịch trước hạn; quá hạn thì hợp đồng hết hiệu lực."
        )
    cho_nha_dau_tu = next((h for h in hop_dong if _la("contract", h["trang_thai"], "PENDING_LENDER_SIGNATURES")), None)
    if cho_nha_dau_tu:
        return (
            f"Chưa có hợp đồng nào cần bạn ký. Hợp đồng {cho_nha_dau_tu['ma_hop_dong']} đang chờ các nhà đầu tư ký; "
            "khi đủ chữ ký, app sẽ mở lượt ký cho bạn."
        )
    dang_smartca = next((h for h in hop_dong if _la("contract", h["trang_thai"], "SIGNING")), None)
    if dang_smartca:
        return (
            f"Hợp đồng {dang_smartca['ma_hop_dong']} đã gửi yêu cầu ký tới SmartCA và đang chờ bạn xác nhận trong "
            "ứng dụng SmartCA."
        )
    return "Hiện bạn không có hợp đồng nào cần ký. Bảng bên dưới liệt kê các hợp đồng gần nhất."


def _cau_qua_han(khoan: Mapping[str, Any]) -> str:
    qh = khoan["qua_han"]
    return (
        f"Khoản {khoan['ma_khoan_vay']} đang quá hạn {qh['so_ngay']} ngày với số tiền quá hạn {qh['so_tien']}, "
        f"tương ứng {qh['nhom_no']} theo cách FINORA suy ra từ số ngày quá hạn."
    )


def _cau_ky(khoan: Mapping[str, Any]) -> str | None:
    if "ky_toi" in khoan:
        ky = khoan["ky_toi"]
        return f"Kỳ tới của khoản {khoan['ma_khoan_vay']} là ngày {ky['ngay']}, số tiền {ky['so_tien']}."
    if "ky_chua_tra" in khoan and "qua_han" not in khoan:
        # Ngày đã qua nhưng Loan báo không quá hạn: số liệu chưa kịp cập nhật khoản vừa trả.
        ky = khoan["ky_chua_tra"]
        return (f"Kỳ gần nhất của khoản {khoan['ma_khoan_vay']} là ngày {ky['ngay']} (đã qua), số tiền {ky['so_tien']}; "
                "số liệu có thể chưa gồm khoản bạn vừa trả.")
    return None


def _render_ky_toi(result: ToolResult) -> str:
    khoan_vay = result.facts.get("khoan_vay") or []
    if not khoan_vay:
        return f"{KHONG_CO_KHOAN_VAY} Khoản vay chỉ xuất hiện sau khi hợp đồng có hiệu lực và tiền đã giải ngân."
    cau: list[str | None] = []
    qua_han = [k for k in khoan_vay if "qua_han" in k]
    co_ky_toi = [k for k in khoan_vay if "ky_toi" in k]
    dang_mo = result.facts.get("khoan_dang_mo")
    if dang_mo:
        # Màn chi tiết khoản vay: trả lời về khoản đang mở trước (fix-2), khoản khác chỉ nhắc nếu đang quá hạn.
        khoan = next(k for k in khoan_vay if k["ma_khoan_vay"] == dang_mo)
        if "qua_han" in khoan:
            cau.append(_cau_qua_han(khoan))
        cau.append(_cau_ky(khoan) or (None if "qua_han" in khoan else
                                      f"Khoản {dang_mo} hiện không có kỳ trả nào sắp tới ({khoan['trang_thai']})."))
        khac = [k for k in qua_han if k is not khoan]
        if khac:
            cau.append(f"Bạn còn {len(khac)} khoản khác đang quá hạn, xem bảng bên dưới.")
    else:
        cau.extend(_cau_qua_han(k) for k in qua_han[:2])
        if co_ky_toi:
            cau.append(_cau_ky(co_ky_toi[0]))
            if len(co_ky_toi) > 1:
                cau.append(f"Bạn còn {len(co_ky_toi) - 1} khoản khác có kỳ trả sắp tới, xem bảng bên dưới.")
        elif not qua_han:
            cau.append(next((c for c in map(_cau_ky, khoan_vay) if c), None)
                       or "Hiện không có kỳ trả nào sắp tới: các khoản đã tất toán hoặc đang chờ cập nhật lịch.")
    if qua_han or co_ky_toi:
        cau.append("Tiền trả nợ được trừ từ số dư ví FINORA sau khi bạn xác nhận bằng mã PIN giao dịch.")
    cu = next((k for k in khoan_vay if k.get("du_lieu_cu")), None)
    if cu:
        cau.append(f"Lưu ý: số liệu cập nhật lúc {cu['cap_nhat_luc']}, có thể chưa gồm khoản bạn vừa trả.")
    return _cau(*cau)


def _render_vi_du_tra(result: ToolResult) -> str:
    facts = result.facts
    mo_dau = (
        f"Ví FINORA của bạn có {facts['so_du_kha_dung']} khả dụng; {facts['dang_giu']} đang được giữ cho giao dịch "
        "chờ xử lý nên chưa dùng được."
    )
    trang_thai_vi = _gia_tri_the(result, "Trạng thái ví")
    qua_han = facts.get("qua_han")
    if qua_han:
        mo_ta = (f"Khoản {qua_han['ma_khoan_vay']} đang quá hạn {qua_han['so_ngay']} ngày với số tiền quá hạn "
                 f"{qua_han['so_tien']}")
        if facts.get("du_tra_qua_han") == "có":
            ket_luan = (f"{mo_ta}; số dư này đủ để trả phần quá hạn. Bạn vẫn cần vào màn trả nợ và xác nhận bằng mã "
                        "PIN để thanh toán, trả càng sớm càng tránh phát sinh thêm lãi phạt.")
        else:
            ket_luan = f"{mo_ta}, nên bạn cần nạp thêm ít nhất {facts['con_thieu']} để trả hết phần quá hạn."
        return _cau(mo_dau, f"{trang_thai_vi}." if trang_thai_vi else None, ket_luan)
    ky_toi = facts.get("ky_toi")
    if not ky_toi:
        return _cau(mo_dau, f"{trang_thai_vi}." if trang_thai_vi else None, "Hiện bạn không có kỳ trả nợ nào sắp tới.")
    if facts.get("du_tra_ky_toi") == "có":
        ket_luan = (
            f"Số này đủ cho kỳ tới của khoản {ky_toi['ma_khoan_vay']} ({ky_toi['so_tien']}, ngày {ky_toi['ngay']}). "
            "Bạn vẫn cần vào màn trả nợ và xác nhận bằng mã PIN để thanh toán."
        )
    else:
        ket_luan = (
            f"Kỳ tới của khoản {ky_toi['ma_khoan_vay']} là {ky_toi['so_tien']} vào ngày {ky_toi['ngay']}, nên bạn "
            f"cần nạp thêm ít nhất {facts['con_thieu']}."
        )
    return _cau(mo_dau, f"{trang_thai_vi}." if trang_thai_vi else None, ket_luan)


def _render_pin(result: ToolResult) -> str:
    facts = result.facts
    if facts.get("da_dat_pin") == "Chưa tạo mã PIN":
        return (
            "Bạn chưa tạo mã PIN giao dịch. Mã PIN gồm 6 số, dùng để ký hợp đồng, trả nợ và xác nhận giao dịch; "
            "bạn tạo ở tab Hồ sơ, dòng “Tạo mã PIN”."
        )
    if facts.get("bi_khoa") == "Đang bị khóa":
        den = f" đến {facts['khoa_den']}" if facts.get("khoa_den") else ""
        return (
            f"Mã PIN giao dịch đang bị tạm khóa{den} vì nhập sai nhiều lần liên tiếp. Bạn có thể chờ hết thời gian "
            "khóa, hoặc chọn “Quên mã PIN?” và đặt mã mới bằng mật khẩu đăng nhập; đặt lại xong khóa được gỡ ngay. "
            "Trợ lý không bao giờ hỏi mã PIN của bạn."
        )
    con_lai = _gia_tri_the(result, "Số lần nhập sai còn lại")
    return _cau(
        "Mã PIN giao dịch của bạn không bị khóa.",
        f"Bạn còn {con_lai} lần nhập sai trước khi bị khóa tạm thời." if con_lai else None,
        "Nếu quên mã, chọn “Quên mã PIN?” để đặt lại bằng mật khẩu đăng nhập.",
    )


def render_mo_phong_khoan_vay(result: ToolResult) -> str:
    """Câu mẫu cho câu tính thử một sản phẩm (fix-1): mọi số lấy nguyên từ lịch Loan tính."""
    facts = result.facts
    ky = f"kỳ đầu {facts['ky_dau']}"
    if facts["ky_cao_nhat"] != facts["ky_dau"]:
        ky += f", kỳ cao nhất {facts['ky_cao_nhat']}"
    return _cau(
        f"Tạm tính cho khoản vay {facts['so_tien_vay']} trong {facts['ky_han']}: {ky}; tổng lãi {facts['tong_lai']}, "
        f"tổng phí {facts['tong_phi']}, tổng phải trả {facts['tong_phai_tra']}.",
        facts.get("ghi_chu"),
    )


def render_goi_y_khoan_vay(result: ToolResult) -> str:
    """Câu mẫu so sánh gói (fix-1). Không nhắc lại thu nhập/nợ người dùng gõ: facts vốn không chứa chúng."""
    facts = result.facts
    if facts.get("khop") is False:
        return str(facts.get("ghi_chu") or "Không có gói vay nào phù hợp với số tiền và kỳ hạn này.")
    goi = facts.get("goi") or []
    xep = "tổng lãi thấp nhất" if facts.get("tieu_chi") == "TONG_LAI" else "số phải trả mỗi tháng thấp nhất"
    mo_dau = (
        f"Với khoản vay {facts['so_tien']} trong {facts['ky_han']}, "
        + (f"mình so sánh {len(goi)} gói phù hợp, xếp theo {xep}:" if len(goi) > 1 else "có 1 gói phù hợp:")
    )
    dong = [mo_dau]
    for thu_tu, g in enumerate(goi, start=1):
        ky = f"kỳ đầu {g['ky_dau']}"
        if g["ky_cao_nhat"] != g["ky_dau"]:
            ky += f", kỳ cao nhất {g['ky_cao_nhat']}"
        ty_le = ""
        if "ty_le_tra_no" in g:
            ty_le = f"; trả nợ/thu nhập {g['ty_le_tra_no']}" + (" (cao)" if g.get("vuot_nguong") else "")
        dong.append(
            f"{thu_tu}. {g['ten']} (lãi cơ sở {g['lai_co_so']}): {ky}, tổng lãi {g['tong_lai']}, "
            f"tổng phải trả {g['tong_phai_tra']}{ty_le}."
        )
    if facts.get("goi_loi"):
        dong.append(f"Chưa tính được lịch cho: {', '.join(facts['goi_loi'])}.")
    ghi_chu = facts.get("ghi_chu") or []
    dong.extend([ghi_chu] if isinstance(ghi_chu, str) else ghi_chu)
    return "\n".join(dong)


def _render_san_pham(result: ToolResult) -> str:
    san_pham = result.facts.get("san_pham") or []
    if not san_pham:
        return KHONG_CO_SAN_PHAM
    mo_ta = "; ".join(
        f"{s['ten']} ({s['so_tien']}, {s['ky_han']}, lãi {s['lai_suat_nam']['thap_nhat']}–"
        f"{s['lai_suat_nam']['cao_nhat']}/năm)"
        for s in san_pham[:3]
    )
    return _cau(
        f"FINORA đang mở bán {so_nguyen(len(san_pham))} sản phẩm vay: {mo_ta}.",
        "Bảng bên dưới có đủ danh sách." if len(san_pham) > 3 else None,
        "Lãi suất cuối của từng hồ sơ phụ thuộc kết quả chấm điểm. Muốn biết mỗi tháng trả bao nhiêu, bạn hỏi "
        "ví dụ “Vay 30 triệu 12 tháng mỗi tháng trả bao nhiêu?”.",
    )


# ---------------------------------------------------------------------------
# Ba hằng xuất ra cho registry (hợp đồng Task 6)
# ---------------------------------------------------------------------------

BORROWER_TOOLS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name="ho_so_cua_toi",
        toolset=Toolset.BORROWER,
        description=(
            "Danh sách tối đa 10 hồ sơ vay gần nhất của người đang hỏi: mã hồ sơ, trạng thái, số tiền đề nghị, "
            "ngày nộp. Dùng khi người dùng hỏi chung về hồ sơ mà chưa nêu mã."
        ),
        params_model=NoParams,
        handler=_ho_so_cua_toi,
        lane=Lane.USER_DATA,
        routes=(_R_DS_HO_SO, _R_HOP_DONG, _R_KHOAN_VAY),
    ),
    ToolSpec(
        name="chi_tiet_ho_so",
        toolset=Toolset.BORROWER,
        description=(
            "Chi tiết một hồ sơ vay của người đang hỏi theo mã LA-…: trạng thái, xác nhận điều khoản và hạn, "
            "tiến độ gọi vốn, lãi suất cơ sở và lãi suất cuối, hạng giá, mức điều chỉnh, nguồn quyết định, các "
            "bước xử lý gần nhất. Chỉ dùng mã có trong dữ liệu đã lấy hoặc màn hình đang mở."
        ),
        params_model=ChiTietHoSoParams,
        handler=_chi_tiet_ho_so,
        lane=Lane.USER_DATA,
        routes=(_R_HO_SO, _R_LICH_SU, _R_HOP_DONG, _R_KHOAN_VAY),
    ),
    ToolSpec(
        name="hop_dong_cua_toi",
        toolset=Toolset.BORROWER,
        description=(
            "Các hợp đồng vay gần nhất của người đang hỏi: mã hợp đồng, trạng thái ký, hạn ký, số tiền gốc, kỳ hạn, "
            "lãi suất; kèm số hợp đồng đang chờ chính người dùng ký. contractNumber (tùy chọn) đưa hợp đồng đó lên đầu."
        ),
        params_model=HopDongDangMoParams,
        handler=_hop_dong_cua_toi,
        lane=Lane.USER_DATA,
        routes=(_R_HOP_DONG,),
    ),
    ToolSpec(
        name="khoan_vay_cua_toi",
        toolset=Toolset.BORROWER,
        description=(
            "Các khoản vay đã giải ngân của người đang hỏi: trạng thái, kỳ tới (ngày, số tiền), kỳ chưa trả đã quá "
            "hạn, quá hạn (số tiền, số ngày, nhóm nợ suy ra), thời điểm cập nhật số liệu và cờ số liệu có thể chưa "
            "mới. loanNumber (tùy chọn) đưa khoản đó lên đầu."
        ),
        params_model=KhoanDangMoParams,
        handler=_khoan_vay_cua_toi,
        lane=Lane.USER_DATA,
        routes=(_R_KHOAN_VAY,),
        slow=True,
    ),
    ToolSpec(
        name="vi_cua_toi",
        toolset=Toolset.BORROWER,
        description=(
            "Số dư ví FINORA của người đang hỏi: khả dụng và đang giữ; nếu có khoản vay đang trả thì so với phần "
            "quá hạn (nếu đang quá hạn) hoặc kỳ trả gần nhất: đủ hay còn thiếu bao nhiêu. loanNumber (tùy chọn) so "
            "với đúng khoản đó."
        ),
        params_model=KhoanDangMoParams,
        handler=_vi_cua_toi,
        lane=Lane.USER_DATA,
        routes=(_R_VI, _R_KHOAN_VAY),
    ),
    ToolSpec(
        name="trang_thai_pin",
        toolset=Toolset.BORROWER,
        description=(
            "Trạng thái mã PIN giao dịch: đã tạo chưa, có đang bị khóa không và khóa đến khi nào. "
            "Không bao giờ hỏi, nhận hay nhắc lại mã PIN."
        ),
        params_model=NoParams,
        handler=_trang_thai_pin,
        lane=Lane.USER_DATA,
        routes=(_R_PIN,),
    ),
    ToolSpec(
        name="san_pham_vay",
        toolset=Toolset.BORROWER,
        description=(
            "Sản phẩm vay đang mở bán (thông tin công khai): id, tên, khoảng số tiền, khoảng kỳ hạn, lãi suất "
            "thấp nhất/cơ sở/cao nhất mỗi năm, cách trả. Lấy id ở đây trước khi gọi mo_phong_khoan_vay."
        ),
        params_model=NoParams,
        handler=_san_pham_vay,
        lane=Lane.PUBLIC,
        routes=(_R_SAN_PHAM,),
    ),
    ToolSpec(
        name="mo_phong_khoan_vay",
        toolset=Toolset.BORROWER,
        description=(
            "Tính thử lịch trả cho một sản phẩm vay: productId (id từ san_pham_vay), amount (đồng, ví dụ 30 triệu = "
            "30000000), termMonths (tháng). Trả kỳ đầu, kỳ cao nhất, tổng lãi, tổng phí, tổng phải trả theo lãi "
            "suất cơ sở. Chỉ tính toán, không tạo hồ sơ."
        ),
        params_model=MoPhongKhoanVayParams,
        handler=_mo_phong_khoan_vay,
        lane=Lane.PUBLIC,
        routes=(_R_MO_PHONG,),
        slow=True,
    ),
    ToolSpec(
        name="goi_y_khoan_vay",
        toolset=Toolset.BORROWER,
        description=(
            "So sánh tối đa 3 gói vay phù hợp với amount (đồng) và termMonths (tháng) người dùng muốn vay: tính "
            "trả mỗi tháng, tổng lãi của mỗi gói. Nếu người dùng cho biết monthlyIncome (và monthlyDebt nếu có), "
            "kèm cảnh báo tỷ lệ trả nợ trên thu nhập. tieuChi chọn xếp theo tổng lãi thấp nhất (TONG_LAI, mặc "
            "định) hay trả mỗi tháng thấp nhất (TRA_THANG)."
        ),
        params_model=GoiYKhoanVayParams,
        handler=_goi_y_khoan_vay,
        lane=Lane.USER_DATA,
        routes=(_R_SAN_PHAM, _R_MO_PHONG),
        slow=True,
    ),
)

_THEO_HO_SO_DANG_MO = {"applicationNumber": "applicationNumber"}

# Thứ tự khai báo là thứ tự hiện (registry cắt còn 4 mỗi màn), nên gợi ý riêng của màn đứng trước gợi ý chung.
BORROWER_SUGGESTIONS: tuple[SuggestionDef, ...] = (
    SuggestionDef(
        id="borrower.application.status",
        label="Hồ sơ của tôi đang ở bước nào?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"Home"}),
        tool="ho_so_cua_toi",
        params_from_context={},
        render=_render_trang_thai_ho_so,
        keywords=("hồ sơ đang ở", "hồ sơ tới đâu", "hồ sơ đến đâu", "trạng thái hồ sơ", "hồ sơ của tôi"),
        khi_co_ma="borrower.application.detail_status",
    ),
    # Đứng đầu các gợi ý của màn hồ sơ: câu hỏi chung ở màn này ("hồ sơ này đang chờ gì?") dùng câu mẫu trạng
    # thái thay vì câu "vì sao bị từ chối" (fix-2). Câu gõ mã ở màn khác tới đây qua `khi_co_ma` ở trên.
    SuggestionDef(
        id="borrower.application.detail_status",
        label="Hồ sơ này đang ở bước nào?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"ApplicationDetail"}),
        tool="chi_tiet_ho_so",
        params_from_context=_THEO_HO_SO_DANG_MO,
        render=_render_trang_thai_mot_ho_so,
        keywords=("đang ở bước nào", "ở bước nào", "đang chờ gì", "tới đâu rồi", "đến đâu rồi", "hồ sơ này"),
    ),
    SuggestionDef(
        id="borrower.application.why_rejected",
        label="Vì sao hồ sơ bị từ chối?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"ApplicationDetail"}),
        tool="chi_tiet_ho_so",
        params_from_context=_THEO_HO_SO_DANG_MO,
        render=_render_ly_do_tu_choi,
        keywords=("bị từ chối", "không được duyệt", "lý do từ chối", "sao từ chối", "trượt hồ sơ"),
    ),
    SuggestionDef(
        id="borrower.application.not_disbursed",
        label="Đã duyệt sao chưa nhận tiền?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"ApplicationDetail"}),
        tool="chi_tiet_ho_so",
        params_from_context=_THEO_HO_SO_DANG_MO,
        render=_render_chua_giai_ngan,
        keywords=("chưa nhận tiền", "chưa giải ngân", "khi nào nhận tiền", "bao giờ có tiền", "đã duyệt sao chưa"),
    ),
    SuggestionDef(
        id="borrower.application.rate_changed",
        label="Sao lãi suất khác lúc nộp?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"ApplicationDetail"}),
        tool="chi_tiet_ho_so",
        params_from_context=_THEO_HO_SO_DANG_MO,
        render=_render_lai_suat_thay_doi,
        keywords=("lãi suất khác", "lãi suất tăng", "lãi cao hơn", "đổi lãi suất", "lãi suất cuối", "hạng giá"),
    ),
    SuggestionDef(
        id="borrower.loan.next_due",
        label="Kỳ tới tôi trả bao nhiêu?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"Home", "LoanServicingDetail"}),
        tool="khoan_vay_cua_toi",
        params_from_context={},
        render=_render_ky_toi,
        keywords=("kỳ tới", "kỳ này", "đến hạn", "hạn trả", "quá hạn", "nhóm nợ", "còn nợ"),
    ),
    SuggestionDef(
        id="borrower.contract.to_sign",
        label="Tôi có hợp đồng nào cần ký?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"Home", "ContractDetail"}),
        tool="hop_dong_cua_toi",
        params_from_context={},
        render=_render_hop_dong_can_ky,
        keywords=("ký hợp đồng", "hợp đồng cần ký", "chờ ký", "hạn ký", "smartca"),
    ),
    SuggestionDef(
        id="borrower.wallet.enough",
        label="Ví có đủ trả kỳ tới không?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"LoanServicingDetail"}),
        tool="vi_cua_toi",
        params_from_context={},
        render=_render_vi_du_tra,
        keywords=("đủ tiền", "số dư", "ví còn", "nạp thêm", "đủ trả", "tiền bị giữ"),
    ),
    SuggestionDef(
        id="borrower.pin.locked",
        label="Mã PIN của tôi có bị khóa không?",
        toolset=Toolset.BORROWER,
        screens=frozenset({"ContractDetail", "LoanServicingDetail"}),
        tool="trang_thai_pin",
        params_from_context={},
        render=_render_pin,
        keywords=("mã pin", "pin bị khóa", "khóa pin", "quên pin", "sai pin"),
    ),
    SuggestionDef(
        id="borrower.products.overview",
        label="FINORA có những gói vay nào?",
        toolset=Toolset.BORROWER,
        screens=frozenset(),
        tool="san_pham_vay",
        params_from_context={},
        render=_render_san_pham,
        keywords=("sản phẩm vay", "gói vay", "vay được bao nhiêu", "vay tối đa", "kỳ hạn tối đa"),
    ),
)

# Câu hỏi gõ tự do khi đang ở một màn: lấy dữ liệu màn đó trước rồi mới gọi LLM một lần (đường B).
# Không có "Home": app mở trợ lý từ nút nổi Trang chủ nên mọi lượt đều gửi screen=Home; gắn công cụ cho
# Home thì đường B chạy trước C1/C2/C3 và câu nào cũng chỉ có danh sách hồ sơ, LLM không có công cụ
# (đo ở Task 21: "Kỳ tới trả bao nhiêu?" nhận câu trạng thái hồ sơ). Trang chủ không gắn đối tượng nào.
BORROWER_SCREEN_TOOLS: dict[str, ScreenTool] = {
    "ApplicationDetail": ("chi_tiet_ho_so", _THEO_HO_SO_DANG_MO),
    "ContractDetail": ("hop_dong_cua_toi", {}),
    "LoanServicingDetail": ("khoan_vay_cua_toi", {}),
}
