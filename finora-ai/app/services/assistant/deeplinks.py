"""Bảng nút điều hướng cố định của trợ lý (spec mục 13.3).

Bot chỉ đọc nên mọi thao tác (trả nợ, nạp tiền, ký hợp đồng, duyệt hồ sơ) là một nút mở màn có
sẵn. LLM chỉ được chọn khóa trong bảng này; đích (tên màn mobile hoặc path web) do code dựng, nên
model không thể đưa người dùng tới một màn hay URL tùy ý.

Mobile: `screen` là tên màn trong `finora-mobile/src/navigation/types.ts` (các màn nằm trong
stack của tab "Hồ sơ", "Sàn", "Ví"; client tự chọn tab). Tham số luôn là chuỗi; client đổi
`productId`, `amount`, `termMonths`, `listingId` sang số đúng kiểu của navigation.
Web: `path_template` theo route của `finora-web/src/App.tsx`.
"""

import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Literal
from urllib.parse import quote

from app.schemas.assistant import MA_NGHIEP_VU, Action, ActionTarget

_THAM_SO_TRONG_PATH = re.compile(r"\{(\w+)\}")


@dataclass(frozen=True)
class ActionDef:
    key: str
    label: str
    surface: Literal["mobile", "web"]
    screen: str | None
    path_template: str | None
    params: tuple[str, ...]


def _mobile(key: str, label: str, screen: str, *params: str) -> ActionDef:
    return ActionDef(key=key, label=label, surface="mobile", screen=screen, path_template=None, params=params)


def _web(key: str, label: str, path_template: str) -> ActionDef:
    params = tuple(_THAM_SO_TRONG_PATH.findall(path_template))
    return ActionDef(key=key, label=label, surface="web", screen=None, path_template=path_template, params=params)


_DEFINITIONS: tuple[ActionDef, ...] = (
    # ---- Mobile: người vay ----
    _mobile("mo_ho_so", "Mở hồ sơ", "ApplicationDetail", "applicationNumber"),
    _mobile("mo_hop_dong", "Mở hợp đồng", "ContractDetail", "contractNumber"),
    _mobile("mo_khoan_vay", "Mở khoản vay", "LoanServicingDetail", "loanNumber"),
    _mobile("tra_no", "Thanh toán khoản vay", "LoanPayment", "loanNumber"),
    _mobile("tat_toan", "Tất toán trước hạn", "EarlySettlement", "loanNumber"),
    _mobile("tra_truoc", "Trả trước một phần", "PartialPrepayment", "loanNumber"),
    _mobile("gia_han", "Cơ cấu khoản vay", "RescheduleLoan", "loanNumber"),
    _mobile("nap_tien", "Nạp tiền vào ví", "TopUp"),
    _mobile("xem_san_pham", "Xem sản phẩm vay", "ProductDetail", "productId"),
    # ApplyForm cần đủ bốn tham số như khi đi từ màn lịch trả dự kiến (ScheduleScreen).
    _mobile(
        "nop_ho_so", "Nộp hồ sơ vay", "ApplyForm",
        "productId", "amount", "termMonths", "expectedDisbursementDate",
    ),
    # ---- Mobile: nhà đầu tư ----
    _mobile("mo_khoan_san", "Mở khoản vay trên Sàn", "LoanDetail", "loanId"),
    _mobile("so_lenh", "Mở sổ lệnh", "OrderBook", "listingId"),
    _mobile("lenh_cua_toi", "Lệnh của tôi", "MyBookOrders"),
    _mobile("auto_invest", "Cài đặt Auto-Invest", "AutoInvest"),
    _mobile("danh_muc", "Danh mục đầu tư", "Portfolio"),
    _mobile("ky_hop_dong_dau_tu", "Hợp đồng đầu tư cần ký", "InvestContract"),
    # ---- Mobile: chung ----
    _mobile("ekyc", "Xác thực danh tính (eKYC)", "EkycCapture"),
    _mobile("tai_khoan", "Thông tin tài khoản", "AccountInfo"),
    # ---- Web: quản trị ----
    _web("trang_tong_quan", "Tổng quan", "/dashboard"),
    _web("hang_doi_tham_dinh", "Hồ sơ chờ thẩm định", "/loans?status=PENDING_REVIEW"),
    _web("mo_tham_dinh", "Mở trang thẩm định", "/loans/{applicationNumber}/review"),
    _web("van_hanh", "Vận hành khoản vay", "/loans/operations?tab={tab}"),
    _web("goi_von", "Gọi vốn & Notes", "/investments/funding"),
    _web("cho_notes", "Chợ thứ cấp Notes", "/investments/secondary"),
    _web("chinh_sach_ai", "Chính sách đánh giá AI", "/loans/evaluation"),
    _web("khach_hang_kyc", "Khách hàng eKYC", "/customers/kyc"),
)

ACTIONS: dict[str, ActionDef] = {definition.key: definition for definition in _DEFINITIONS}

# Tham số chỉ nhận vài giá trị: tab của trang vận hành (`SERVICING_TABS` trong finora-web).
_GIA_TRI_CHO_PHEP: dict[tuple[str, str], frozenset[str]] = {
    ("van_hanh", "tab"): frozenset({"collection", "reschedule", "reconciliation", "quarantine"}),
}


def build_action(key: str, params: Mapping[str, str] | None = None, label: str | None = None) -> Action:
    """Dựng nút điều hướng từ khóa trong bảng.

    Ném ValueError khi khóa lạ, thiếu tham số, hoặc giá trị không phải mã nghiệp vụ; orchestrator
    bắt lỗi này và bỏ nút thay vì gửi một đích hỏng cho client. Tham số thừa bị bỏ qua.
    """
    definition = ACTIONS.get(key)
    if definition is None:
        raise ValueError(f"Khóa hành động lạ: {key}")
    supplied = params or {}
    missing = [name for name in definition.params if not supplied.get(name)]
    if missing:
        raise ValueError(f"Hành động {key} thiếu tham số: {', '.join(missing)}")
    values: dict[str, str] = {}
    for name in definition.params:
        value = supplied[name]
        if not isinstance(value, str) or not MA_NGHIEP_VU.fullmatch(value):
            raise ValueError(f"Tham số {name} của hành động {key} không hợp lệ")
        allowed = _GIA_TRI_CHO_PHEP.get((key, name))
        if allowed is not None and value not in allowed:
            raise ValueError(f"Tham số {name} của hành động {key} không thuộc danh sách cho phép")
        values[name] = value

    if definition.surface == "mobile":
        target = ActionTarget(screen=definition.screen, params=values)
    else:
        template = definition.path_template or ""
        path = template.format(**{name: quote(value, safe="") for name, value in values.items()})
        target = ActionTarget(path=path)
    return Action(key=key, label=label or definition.label, target=target)


def allowed_action_keys(surface: str) -> list[str]:
    """Khóa hành động của một nơi gửi, để đưa vào prompt hệ thống."""
    return [definition.key for definition in _DEFINITIONS if definition.surface == surface]
