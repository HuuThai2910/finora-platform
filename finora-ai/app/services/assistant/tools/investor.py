"""
Công cụ chỉ đọc cho nhà đầu tư (`Toolset.INVESTOR`) của Trợ lý FINORA.

Mỗi công cụ gọi API của service sở hữu dữ liệu bằng token của chính người hỏi (relay qua
`FinoraApiClient`), lọc trường theo whitelist rồi trả `ToolResult`:

- `facts`: dữ kiện đã định dạng sẵn, là phần DUY NHẤT gửi cho LLM. Không chứa trường tự do (phần mô tả
  mục đích vay do người vay gõ, nội dung thông báo, chi tiết lý do từ chối) để không ai chèn được lệnh
  cho LLM qua dữ liệu nghiệp vụ.
- `cards`: thẻ hiển thị trong app; được chi tiết hơn vì không rời khỏi FINORA.
- `actions`: nút mở màn có sẵn. Bot không tự đặt/hủy lệnh hay ký hợp đồng.

Mọi `facts` có cờ `khong_tu_van=True`: orchestrator dựa vào đó gắn câu miễn trừ "không phải tư vấn đầu
tư" (LAW-CONSUMER-2023 trong docs/LEGAL-COMPLIANCE.md: không gây nhầm lẫn). Danh sách khoản đang gọi
vốn sắp theo hạn đóng gọi vốn chứ không theo lãi suất, để bot không ngầm xếp hạng "khoản tốt nhất".

Tiền: Investment trả chuỗi decimal ("1500000.00"), Payment/Loan trả số JSON; mọi phép cộng trừ đổi về
`Decimal` qua `_tien()` để không lệch đồng lẻ như float.
"""

from __future__ import annotations

import asyncio
import re
from collections import Counter
from collections.abc import Awaitable, Iterable
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from itertools import zip_longest
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from app.schemas.assistant import Action, Card, CardItem
from app.services.assistant.deeplinks import build_action
from app.services.assistant.formatting import ngay_gio_vn, ngay_vn, phan_tram, so_nguyen, tien_vnd
from app.services.assistant.glossary import dich_ma_loi, dich_trang_thai
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

# ----------------------------------------------------------------- đường dẫn API (khớp controller Java)

_PORTFOLIO = "/api/v1/investments/portfolio"
_COMMITMENTS = "/api/v1/investments/commitments"
_MY_ORDERS = "/api/v1/investments/order-books/orders/mine"
_ORDER_BOOK = "/api/v1/investments/order-books/{listing_id}"
_AUTO_INVEST = "/api/v1/investments/auto-invest"
_AUTO_INVEST_MATCHES = "/api/v1/investments/auto-invest/matches"
_LISTINGS = "/api/v1/market/listings"
_LISTING = "/api/v1/market/listings/{listing_id}"
_LISTING_PROGRESS = "/api/v1/market/listings/{listing_id}/progress"
_WALLET = "/api/v1/wallets/me"
_INVESTOR_CONTRACTS = "/api/v1/investor/loan-contracts/me"
_NOTIFICATIONS = "/api/v1/notifications"

# ----------------------------------------------------------------- giới hạn và bảng tra

# Số dòng tối đa đưa vào facts/thẻ: đủ trả lời mà prompt vẫn dưới ~2.000 token (spec mục 7.4).
_TOI_DA_DONG = 10
# @Max(100) của MarketController/OrderBookController: lấy một trang lớn để tự sắp lại (xem khoan_dang_goi_von).
_TRANG_LON = 100
_SO_MUC_GIA = 5
_DU_LIEU_HONG = "Dữ liệu trả về chưa đúng định dạng, bạn thử lại sau ít phút."
_MA_NGHIEP_VU = re.compile(r"[A-Za-z0-9-]{1,50}")
_LENH_CON_HIEU_LUC = frozenset({"PENDING_FUNDS", "OPEN", "PARTIALLY_FILLED"})
_HANG = frozenset({"A", "B", "C", "D", "E"})
_CHIEU = {"BID": "Mua", "ASK": "Bán"}
_HD_CHO_KY = frozenset({"PENDING_LENDER_SIGNATURES", "PENDING_SIGNATURE", "SIGNING"})
_XA_NHAT = datetime.max.replace(tzinfo=timezone.utc)

# Nhãn của enum LoanPurpose (finora-loan). Listing.purpose = nhãn + ": " + mô tả tự do của người vay;
# chỉ nhãn nằm trong tập này mới được đi tiếp.
_NHAN_MUC_DICH = frozenset({
    "Hợp nhất các khoản nợ",
    "Thanh toán dư nợ thẻ tín dụng",
    "Sửa chữa nhà",
    "Mua sắm tài sản có giá trị",
    "Chi phí y tế",
    "Mua hoặc sửa chữa xe",
    "Vốn kinh doanh nhỏ",
    "Chi phí chuyển nơi ở",
    "Du lịch",
    "Chi phí giáo dục",
    "Mục đích khác",
})

# RepaymentMethod của finora-loan; cách tính khớp investRules.ts của app.
_CACH_TRA = {
    "ANNUITY": "Trả góp đều: gốc + lãi bằng nhau mỗi kỳ",
    "EQUAL_PRINCIPAL": "Gốc chia đều mỗi kỳ, lãi tính trên dư nợ giảm dần",
}

# Nhóm nợ nội bộ tham chiếu, docs/LOAN-SERVICING-POLICY.md mục 4 (benchmark nội bộ của đồ án).
_TEN_NHOM_NO = {1: "Đủ tiêu chuẩn", 2: "Cần chú ý", 3: "Dưới tiêu chuẩn", 4: "Nghi ngờ",
                5: "Có khả năng mất vốn"}
_GIAI_THICH_NHOM_NO = (
    "Nhóm nợ nội bộ suy từ số ngày quá hạn (DPD): 0–9 ngày là nhóm 1, 10–90 ngày nhóm 2, 91–180 ngày "
    "nhóm 3, 181–360 ngày nhóm 4, trên 360 ngày nhóm 5; từ nhóm 3 trở lên là nợ xấu nội bộ."
)

# changeType của InvestorServicingNotificationService (finora-notification).
_LOAI_THONG_BAO = {
    "REPAYMENT_CREDITED": "Nhận tiền trả nợ",
    "EARLY_SETTLEMENT": "Khoản vay tất toán sớm",
    "SETTLED": "Khoản vay đã tất toán",
    "RESCHEDULED": "Khoản vay được cơ cấu lịch trả",
    "DELINQUENCY_CHANGED": "Khoản vay quá hạn",
    "DELINQUENCY_CURED": "Khoản vay hết quá hạn",
    "BAD_DEBT_MILESTONE": "Cập nhật rủi ro nợ xấu",
}
# Chỉ rút con số từ mẫu câu cố định của finora-notification; bản thân nội dung không vào facts.
_RE_NHOM_NO = re.compile(r"nhóm nợ (\d)")
_RE_SO_NGAY = re.compile(r"(?:DPD|quá hạn) (\d{1,5})")
_RE_SO_TIEN = re.compile(r"phân bổ (\d{1,15}(?:\.\d{1,2})?) VND")
_RE_DAO_HAN = re.compile(r"đáo hạn mới (\d{4}-\d{2}-\d{2})")

# Đúng điều kiện AutoInvestConfig.accepts + AutoInvestConfig.orderAmount (finora-investment).
_QUY_TAC_AUTO_INVEST = (
    "Auto-Invest chỉ xét khoản mở gọi vốn từ lúc bạn bật trở đi, đúng hạng đã chọn, lãi suất từ mức tối "
    "thiểu trở lên và kỳ hạn không quá mức tối đa; mỗi khoản đặt không quá số tiền đã cấu hình, làm tròn "
    "xuống theo mệnh giá Note."
)

_CACH_DOC_GIA = (
    "Giá tính theo % dư nợ gốc còn lại của mỗi Note. Lệnh mua khớp khi có người bán chào bằng hoặc thấp "
    "hơn giá mua; lệnh bán khớp khi có người mua trả bằng hoặc cao hơn giá bán."
)


# ----------------------------------------------------------------- tham số do LLM/ngữ cảnh truyền


class _ThamSo(BaseModel):
    """Tên tham số camelCase đúng như LLM và `context.params` thấy; cấm khóa lạ để LLM không nhét
    thêm `investorId` hay tham số lọc ngoài danh sách."""

    model_config = ConfigDict(extra="forbid", alias_generator=to_camel, populate_by_name=True)


class ListingIdParams(_ThamSo):
    listing_id: int = Field(
        ge=1, le=2_147_483_647,
        description="Mã đợt gọi vốn (listingId), chính là số trên màn 'Khoản vay #…' của app",
    )


class ListingFilterParams(_ThamSo):
    grade: Literal["A", "B", "C", "D", "E"] | None = Field(
        default=None, description="Hạng tín dụng A–E người dùng tự chọn; bỏ trống nếu người dùng không nêu")
    min_rate: float | None = Field(
        default=None, ge=0, le=20, description="Lãi suất tối thiểu, %/năm (ví dụ 12.5)")
    max_term_months: int | None = Field(
        default=None, ge=1, le=60, description="Kỳ hạn tối đa, số tháng")

    @field_validator("grade", mode="before")
    @classmethod
    def _hang_viet_hoa(cls, value: Any) -> Any:
        """Người dùng gõ "hạng b" thì LLM hay truyền "b"; chuẩn hóa trước khi so với Literal."""
        return value.strip().upper() if isinstance(value, str) else value


# ----------------------------------------------------------------- tiện ích đọc dữ liệu an toàn


def _hong() -> ToolError:
    return ToolError("DU_LIEU_KHONG_HOP_LE", _DU_LIEU_HONG)


def _obj(data: Any) -> dict[str, Any]:
    if not isinstance(data, dict):
        raise _hong()
    return data


def _ds(data: Any) -> list[dict[str, Any]]:
    """Danh sách JSON (List<...> của Java). null coi như rỗng; phần tử không phải object là dữ liệu hỏng."""
    if data is None:
        return []
    if not isinstance(data, list) or not all(isinstance(item, dict) for item in data):
        raise _hong()
    return data


def _trang(data: Any) -> tuple[list[dict[str, Any]], int]:
    """PageResponse của finora-common → (content, totalElements)."""
    trang = _obj(data)
    noi_dung = _ds(trang.get("content"))
    tong = trang.get("totalElements")
    if not isinstance(tong, int) or isinstance(tong, bool):
        tong = len(noi_dung)
    return noi_dung, tong


def _tien(value: Any) -> Decimal:
    """Đổi tiền/tỷ lệ về Decimal. null là chưa phát sinh (0); chuỗi không phải số là dữ liệu hỏng."""
    if value is None:
        return Decimal(0)
    if isinstance(value, bool) or not isinstance(value, (str, int, float, Decimal)):
        raise _hong()
    try:
        so = Decimal(str(value))
    except InvalidOperation as exc:
        raise _hong() from exc
    if not so.is_finite():
        raise _hong()
    return so


def _so_dem(value: Any) -> int:
    """Số đếm (ngày quá hạn, số Note...). Thiếu hoặc sai kiểu coi là 0 thay vì làm hỏng cả câu trả lời."""
    return value if isinstance(value, int) and not isinstance(value, bool) and value > 0 else 0


def _ma(value: Any) -> str | None:
    """Mã số (listingId, loanId) dạng chuỗi để so với `context.params` và chống model bịa mã."""
    if isinstance(value, int) and not isinstance(value, bool) and value > 0:
        return str(value)
    return None


def _ma_chu(value: Any) -> str | None:
    """Mã nghiệp vụ dạng chữ (mã lệnh, mã hợp đồng). Giá trị lạ bị bỏ để không mang chuỗi tùy ý vào prompt."""
    if isinstance(value, str) and _MA_NGHIEP_VU.fullmatch(value):
        return value
    return None


def _hang(value: Any) -> str:
    return value if isinstance(value, str) and value in _HANG else "—"


def _pt(value: Any) -> str:
    return "—" if value is None else phan_tram(_tien(value))


def _lai(value: Any) -> str:
    return "—" if value is None else f"{_pt(value)}/năm"


def _ky_han(value: Any) -> str:
    return f"{so_nguyen(value)} tháng" if isinstance(value, int) and not isinstance(value, bool) else "—"


def _gia(value: Any) -> str:
    """Giá trên sổ lệnh là % dư nợ gốc còn lại của mỗi Note ("97.5" = 97,5%)."""
    return "Chưa có" if value is None else f"{_pt(value)} dư nợ gốc"


def _muc_dich(value: Any) -> str:
    """Chỉ giữ nhãn mục đích chuẩn; phần mô tả tự do sau dấu ':' không bao giờ đi tiếp."""
    if not isinstance(value, str):
        return "—"
    nhan = value.split(":", 1)[0].strip()
    return nhan if nhan in _NHAN_MUC_DICH else "—"


def _nhom_no(value: Any) -> str:
    if not isinstance(value, int) or isinstance(value, bool) or value not in _TEN_NHOM_NO:
        return "—"
    ten = _TEN_NHOM_NO[value]
    return f"Nhóm {value} ({ten}, nợ xấu nội bộ)" if value >= 3 else f"Nhóm {value} ({ten})"


def _thoi_diem(value: Any) -> datetime | None:
    """Instant của Java ("…Z", có thể có phần lẻ giây). Dùng để sắp xếp; so chuỗi sẽ sai khi lẻ giây."""
    if not isinstance(value, str):
        return None
    try:
        moc = datetime.fromisoformat(value)
    except ValueError:
        return None
    return moc if moc.tzinfo else moc.replace(tzinfo=timezone.utc)


def _thoi_gian(value: Any) -> str:
    return ngay_gio_vn(value) if _thoi_diem(value) else "—"


def _ngay(value: Any) -> str:
    if not isinstance(value, str):
        return "—"
    try:
        date.fromisoformat(value)
    except ValueError:
        return "—"
    return ngay_vn(value)


def _cau(text: str) -> str:
    """Bảo đảm câu kết bằng dấu chấm khi ghép nối các mảnh từ glossary."""
    text = text.strip()
    return text if text.endswith((".", "!", "?")) else f"{text}."


async def _gom(*viec: Awaitable[Any]) -> list[Any]:
    """Chạy song song các lời gọi API độc lập (số lời gọi cố định, không N+1).

    Đợi tất cả xong rồi mới ném lỗi đầu tiên: nếu ném ngay, các request còn lại vẫn chạy ngầm trên
    client sắp bị đóng."""
    ket_qua = await asyncio.gather(*viec, return_exceptions=True)
    for item in ket_qua:
        if isinstance(item, BaseException):
            raise item
    return list(ket_qua)


def _ket_qua(facts: dict[str, Any], *, cards: Iterable[Card] = (), actions: Iterable[Action] = (),
             ids: Iterable[str | None] = ()) -> ToolResult:
    """Gắn cờ không tư vấn cho mọi kết quả của nhà đầu tư và gom mã nghiệp vụ đã thấy."""
    return ToolResult(
        facts={"khong_tu_van": True, **facts},
        cards=list(cards),
        actions=list(actions),
        ids=frozenset(ma for ma in ids if ma),
    )


def _the_kv(tieu_de: str, cap: Iterable[tuple[str, str]]) -> Card:
    return Card(kind="kv", title=tieu_de, items=[CardItem(label=nhan, value=gia_tri) for nhan, gia_tri in cap])


def _the_bang(tieu_de: str, cot: list[str], dong: list[list[str]]) -> Card:
    return Card(kind="table", title=tieu_de, columns=cot, rows=dong)


# ----------------------------------------------------------------- danh_muc_cua_toi


def _vi_the(vi_the: dict[str, Any]) -> dict[str, Any]:
    return {
        "ma_khoan": _ma(vi_the.get("listingId")) or "—",
        "ma_khoan_vay": _ma(vi_the.get("loanId")) or "—",
        "muc_dich": _muc_dich(vi_the.get("purpose")),
        "hang": _hang(vi_the.get("creditGrade")),
        "lai_suat": _lai(vi_the.get("annualInterestRate")),
        "ky_han": _ky_han(vi_the.get("termMonths")),
        "so_note": so_nguyen(_so_dem(vi_the.get("noteCount"))),
        "du_no_goc": tien_vnd(_tien(vi_the.get("outstandingPrincipal"))),
        "goc_da_thu": tien_vnd(_tien(vi_the.get("principalRepaid"))),
        "lai_da_nhan": tien_vnd(_tien(vi_the.get("interestReceived"))),
        "trang_thai_note": dich_trang_thai("note", vi_the.get("status")),
        "nhom_no": _nhom_no(vi_the.get("debtGroup")),
        "so_ngay_qua_han": so_nguyen(_so_dem(vi_the.get("daysPastDue"))),
        "so_tien_qua_han": tien_vnd(_tien(vi_the.get("overdueAmount"))),
        "ngay_dao_han": _ngay(vi_the.get("maturityDate")),
    }


def _tinh_trang_vi_the(vi_the: dict[str, Any]) -> str:
    """Cụm chữ cho cột "Tình trạng" của thẻ: nợ xấu, rồi quá hạn, rồi đúng hạn."""
    so_ngay = _so_dem(vi_the.get("daysPastDue"))
    if vi_the.get("status") == "DEFAULTED":
        nhom = vi_the.get("debtGroup")
        return f"Nợ xấu, nhóm {nhom}" if isinstance(nhom, int) and not isinstance(nhom, bool) else "Nợ xấu"
    if so_ngay > 0:
        return f"Quá hạn {so_nguyen(so_ngay)} ngày"
    return "Đúng hạn"


async def _danh_muc_cua_toi(client: FinoraApiClient, _params: NoParams) -> ToolResult:
    data = _obj(await client.get(ServiceName.INVESTMENT, _PORTFOLIO))
    vi_the = _ds(data.get("positions"))
    no_xau = [item for item in vi_the if item.get("status") == "DEFAULTED"]
    qua_han = [item for item in vi_the if _so_dem(item.get("daysPastDue")) > 0]
    # Task 4 thêm ba trường tổng nợ xấu; backend chưa cập nhật thì tự tính từ positions.
    if "defaultedAmount" in data:
        du_no_xau = _tien(data.get("defaultedAmount"))
        so_note_xau = _so_dem(data.get("defaultedNoteCount"))
        so_khoan_xau = _so_dem(data.get("defaultedPositionCount"))
    else:
        du_no_xau = sum((_tien(item.get("outstandingPrincipal")) for item in no_xau), Decimal(0))
        so_note_xau = sum(_so_dem(item.get("noteCount")) for item in no_xau)
        so_khoan_xau = len(no_xau)
    von_cho = _tien(data.get("pendingAmount"))
    # Khoản nợ xấu rồi quá hạn lên đầu vì đó là điều nhà đầu tư cần biết trước. Đây là thứ tự đọc,
    # không phải xếp hạng khoản tốt/xấu để mua bán.
    sap_xep = sorted(vi_the, key=lambda item: (item.get("status") != "DEFAULTED",
                                               -_so_dem(item.get("daysPastDue")),
                                               _so_dem(item.get("loanId"))))
    hien = sap_xep[:_TOI_DA_DONG]
    facts: dict[str, Any] = {
        "von_cho_giai_ngan": tien_vnd(von_cho),
        "co_von_cho_giai_ngan": von_cho > 0,
        "von_dang_dau_tu": tien_vnd(_tien(data.get("investedAmount"))),
        "goc_da_thu": tien_vnd(_tien(data.get("principalRepaid"))),
        "lai_da_nhan": tien_vnd(_tien(data.get("interestReceived"))),
        "tong_da_nhan": tien_vnd(_tien(data.get("totalReceived"))),
        "so_note_dang_hoat_dong": so_nguyen(_so_dem(data.get("activeNoteCount"))),
        "so_khoan": so_nguyen(len(vi_the)),
        "lai_suat_binh_quan": _lai(data.get("weightedAverageRate")),
        "so_khoan_no_xau": so_nguyen(so_khoan_xau),
        "so_note_no_xau": so_nguyen(so_note_xau),
        "du_no_khoan_no_xau": tien_vnd(du_no_xau),
        "co_no_xau": so_khoan_xau > 0,
        "so_khoan_qua_han": so_nguyen(len(qua_han)),
        "co_qua_han": bool(qua_han),
        "vi_the": [_vi_the(item) for item in hien],
        "so_khoan_khong_liet_ke": so_nguyen(len(vi_the) - len(hien)),
    }
    tong_quan = [
        ("Vốn chờ giải ngân", facts["von_cho_giai_ngan"]),
        ("Vốn đang đầu tư", facts["von_dang_dau_tu"]),
        ("Gốc đã thu", facts["goc_da_thu"]),
        ("Lãi đã nhận", facts["lai_da_nhan"]),
        ("Lãi suất bình quân", facts["lai_suat_binh_quan"]),
        ("Số khoản", f"{facts['so_khoan']} khoản · {facts['so_note_dang_hoat_dong']} Note đang trả"),
    ]
    if so_khoan_xau:
        tong_quan.append(("Khoản nợ xấu", f"{facts['so_khoan_no_xau']} khoản · dư nợ {facts['du_no_khoan_no_xau']}"))
    cards = [_the_kv("Danh mục của bạn", tong_quan)]
    if hien:
        cards.append(_the_bang(
            "Các khoản đang nắm", ["Khoản", "Hạng", "Lãi/năm", "Dư nợ gốc", "Tình trạng"],
            [[f"#{_ma(item.get('listingId')) or '—'}", _hang(item.get("creditGrade")),
              _pt(item.get("annualInterestRate")), tien_vnd(_tien(item.get("outstandingPrincipal"))),
              _tinh_trang_vi_the(item)] for item in hien],
        ))
    ids = [_ma(item.get("listingId")) for item in vi_the] + [_ma(item.get("loanId")) for item in vi_the]
    return _ket_qua(facts, cards=cards, actions=[build_action("danh_muc")], ids=ids)


# ----------------------------------------------------------------- tien_dang_giu


def _con_giu(lenh: dict[str, Any]) -> Decimal:
    """Phần tiền giữ chưa dùng của một lệnh mua = giữ ban đầu − đã dùng cho các lần khớp."""
    return max(_tien(lenh.get("holdAmount")) - _tien(lenh.get("holdConsumed")), Decimal(0))


def _ghi_chu_chenh_lech(chenh_lech: Decimal) -> str:
    if chenh_lech == 0:
        return "Toàn bộ tiền đang giữ đã khớp với các khoản kể trên."
    if chenh_lech > 0:
        return (f"Còn {tien_vnd(chenh_lech)} chưa gắn được với khoản nào: thường là tiền của lệnh mua vừa "
                "khớp đang chờ chuyển cho người bán, hoặc phần giữ thừa của lệnh vừa kết thúc đang được trả "
                "về ví; số này tự khớp lại sau ít phút.")
    return (f"Sàn ghi nhận nhiều hơn số ví đang giữ {tien_vnd(-chenh_lech)}: một khoản vừa giải ngân hoặc "
            "một lệnh vừa đặt đang được cập nhật giữa ví và sàn; bạn thử hỏi lại sau ít phút.")


async def _tien_dang_giu(client: FinoraApiClient, _params: NoParams) -> ToolResult:
    # Bốn nguồn độc lập nên gọi song song. Ví là số chuẩn (Payment giữ tiền thật); ba nguồn còn lại
    # của Investment giải thích số đó đi đâu.
    vi, danh_muc, cam_ket, lenh = await _gom(
        client.get(ServiceName.PAYMENT, _WALLET),
        client.get(ServiceName.INVESTMENT, _PORTFOLIO),
        client.get(ServiceName.INVESTMENT, _COMMITMENTS),
        client.get(ServiceName.INVESTMENT, _MY_ORDERS, {"active": "true", "page": 0, "size": _TRANG_LON}),
    )
    vi = _obj(vi)
    kha_dung = _tien(vi.get("available"))
    dang_giu = _tien(vi.get("held"))
    # pendingAmount (sau Task 4) = commitment ACTIVE/FINALIZED chưa có Note nào. CommitmentResponse không
    # cho biết FINALIZED đã có Note chưa, nên phần "đã khóa chờ giải ngân" là hiệu của hai con số.
    giu_goi_von = _tien(_obj(danh_muc).get("pendingAmount"))
    cam_ket_mo = [item for item in _ds(cam_ket) if item.get("status") == "ACTIVE"]
    dang_goi_von = sum((_tien(item.get("amount")) for item in cam_ket_mo), Decimal(0))
    da_khoa = max(giu_goi_von - dang_goi_von, Decimal(0))
    lenh_ds, tong_lenh = _trang(lenh)
    lenh_mua = [item for item in lenh_ds
                if item.get("side") == "BID" and item.get("status") in _LENH_CON_HIEU_LUC
                and not item.get("holdReleased")]
    trong_so = [item for item in lenh_mua if item.get("status") != "PENDING_FUNDS"]
    cho_vi = [item for item in lenh_mua if item.get("status") == "PENDING_FUNDS"]
    giu_lenh = sum((_con_giu(item) for item in trong_so), Decimal(0))
    giu_cho_vi = sum((_con_giu(item) for item in cho_vi), Decimal(0))
    chenh_lech = dang_giu - giu_goi_von - giu_lenh - giu_cho_vi

    chi_tiet = [{"loai": "Đang gọi vốn", "ma": f"Khoản #{_ma(item.get('listingId')) or '—'}",
                 "so_tien": tien_vnd(_tien(item.get("amount")))} for item in cam_ket_mo]
    if da_khoa > 0:
        chi_tiet.append({"loai": "Đã khóa chờ giải ngân", "ma": "—", "so_tien": tien_vnd(da_khoa)})
    chi_tiet += [{"loai": "Lệnh mua chờ ví xác nhận" if item.get("status") == "PENDING_FUNDS"
                  else "Lệnh mua chờ khớp",
                  "ma": _ma_chu(item.get("orderReference")) or "—",
                  "so_tien": tien_vnd(_con_giu(item))} for item in lenh_mua]
    facts: dict[str, Any] = {
        "so_du_kha_dung": tien_vnd(kha_dung),
        "tong_dang_giu": tien_vnd(dang_giu),
        "co_tien_giu": dang_giu > 0,
        "giu_cho_goi_von": tien_vnd(giu_goi_von),
        "co_giu_goi_von": giu_goi_von > 0,
        "trong_do_dang_goi_von": tien_vnd(dang_goi_von),
        "so_khoan_dang_goi_von": so_nguyen(len(cam_ket_mo)),
        "trong_do_da_khoa_cho_giai_ngan": tien_vnd(da_khoa),
        "giu_cho_lenh_mua": tien_vnd(giu_lenh),
        "so_lenh_mua_dang_cho": so_nguyen(len(trong_so)),
        "co_giu_lenh_mua": bool(trong_so),
        "dang_cho_vi_xac_nhan": tien_vnd(giu_cho_vi),
        "co_cho_xac_nhan": bool(cho_vi),
        "chenh_lech": tien_vnd(abs(chenh_lech)),
        "ghi_chu_chenh_lech": _ghi_chu_chenh_lech(chenh_lech),
        "chi_tiet": chi_tiet[:_TOI_DA_DONG],
    }
    if tong_lenh > len(lenh_ds):
        facts["ghi_chu_lenh"] = (f"Chỉ tính {so_nguyen(len(lenh_ds))} lệnh còn hiệu lực gần nhất trên tổng "
                                 f"{so_nguyen(tong_lenh)} lệnh.")
    the_vi = [
        ("Khả dụng", facts["so_du_kha_dung"]),
        ("Đang giữ", facts["tong_dang_giu"]),
        ("Giữ cho gọi vốn", facts["giu_cho_goi_von"]),
        ("Giữ cho lệnh mua", facts["giu_cho_lenh_mua"]),
    ]
    if cho_vi:
        the_vi.append(("Chờ ví xác nhận", facts["dang_cho_vi_xac_nhan"]))
    if chenh_lech != 0:
        the_vi.append(("Chưa đối chiếu", facts["chenh_lech"]))
    cards = [_the_kv("Tiền trong ví", the_vi)]
    if chi_tiet:
        cards.append(_the_bang("Chi tiết khoản giữ", ["Loại", "Mã", "Số tiền"],
                               [[dong["loai"], dong["ma"], dong["so_tien"]] for dong in chi_tiet[:_TOI_DA_DONG]]))
    actions = []
    if lenh_mua:
        actions.append(build_action("lenh_cua_toi"))
    if giu_goi_von > 0:
        actions.append(build_action("danh_muc"))
    ids = [_ma(item.get("listingId")) for item in cam_ket_mo] + [_ma_chu(item.get("orderReference")) for item in lenh_mua]
    return _ket_qua(facts, cards=cards, actions=actions, ids=ids)


# ----------------------------------------------------------------- lenh_cua_toi


def _ly_do_huy(ma: Any) -> str:
    """`USER` quá chung để đặt vào bảng mã lỗi dùng chung của glossary (dễ trùng nghĩa mã khác), nên dịch
    tại chỗ; các lý do còn lại của CancelReason đi qua glossary như spec mục 9.4."""
    if ma == "USER":
        return "Bạn đã tự hủy phần chưa khớp."
    return dich_ma_loi(ma if isinstance(ma, str) else None)


def _lenh(lenh: dict[str, Any]) -> dict[str, Any]:
    con_hieu_luc = lenh.get("status") in _LENH_CON_HIEU_LUC
    item: dict[str, Any] = {
        "ma_lenh": _ma_chu(lenh.get("orderReference")) or "—",
        "ma_khoan": _ma(lenh.get("listingId")) or "—",
        "chieu": _CHIEU.get(lenh.get("side"), "—"),
        "gia": _gia(lenh.get("pricePercent")),
        "so_note_dat": so_nguyen(_so_dem(lenh.get("quantity"))),
        "so_note_da_khop": so_nguyen(_so_dem(lenh.get("filledQuantity"))),
        "so_note_con_lai": so_nguyen(_so_dem(lenh.get("remainingQuantity"))),
        "trang_thai": dich_trang_thai("book_order", lenh.get("status")),
        "con_hieu_luc": con_hieu_luc,
        "dat_luc": _thoi_gian(lenh.get("createdAt")),
    }
    if lenh.get("cancelReason"):
        item["ly_do_huy"] = _ly_do_huy(lenh.get("cancelReason"))
    if isinstance(lenh.get("rejectReasonCode"), str) and lenh.get("rejectReasonCode"):
        # Chỉ dịch mã; rejectReasonDetail là câu chữ tự do từ Payment nên không đưa vào facts.
        item["ly_do_tu_choi"] = dich_ma_loi(lenh.get("rejectReasonCode"))
    if lenh.get("side") == "BID" and lenh.get("holdAmount") is not None:
        item["tien_giu_ban_dau"] = tien_vnd(_tien(lenh.get("holdAmount")))
        item["tien_giu_da_dung"] = tien_vnd(_tien(lenh.get("holdConsumed")))
        if lenh.get("holdReleased"):
            item["phan_giu_con_lai"] = "Đã trả về ví"
        else:
            trang_thai_giu = "đang giữ cho phần chưa khớp" if con_hieu_luc else "đang được trả về ví"
            item["phan_giu_con_lai"] = f"{tien_vnd(_con_giu(lenh))} ({trang_thai_giu})"
    return item


async def _lenh_cua_toi(client: FinoraApiClient, _params: NoParams) -> ToolResult:
    lenh_ds, _tong = _trang(await client.get(ServiceName.INVESTMENT, _MY_ORDERS, {"page": 0, "size": 20}))
    items = [_lenh(lenh) for lenh in lenh_ds]
    # Lệnh cần giải thích (còn chờ, bị hủy, bị từ chối) lên trước; trong từng nhóm giữ thứ tự mới nhất.
    can_giai_thich = [item for item in items
                      if item["con_hieu_luc"] or "ly_do_huy" in item or "ly_do_tu_choi" in item]
    con_lai = [item for item in items if item not in can_giai_thich]
    hien = (can_giai_thich + con_lai)[:_TOI_DA_DONG]
    facts: dict[str, Any] = {
        "so_lenh_gan_day": so_nguyen(len(items)),
        "so_lenh_dang_cho": so_nguyen(sum(1 for item in items if item["con_hieu_luc"])),
        "so_lenh_huy_hoac_tu_choi": so_nguyen(
            sum(1 for item in items if "ly_do_huy" in item or "ly_do_tu_choi" in item)),
        "lenh": hien,
    }
    cards = []
    if hien:
        cards.append(_the_bang(
            "Lệnh gần đây của bạn", ["Mã lệnh", "Khoản", "Chiều", "Giá", "Khớp", "Trạng thái"],
            [[item["ma_lenh"], f"#{item['ma_khoan']}", item["chieu"], item["gia"],
              f"{item['so_note_da_khop']}/{item['so_note_dat']}", item["trang_thai"]] for item in hien],
        ))
        ly_do = [(item["ma_lenh"], item.get("ly_do_tu_choi") or item["ly_do_huy"])
                 for item in hien if "ly_do_huy" in item or "ly_do_tu_choi" in item][:3]
        if ly_do:
            cards.append(_the_kv("Lý do hủy hoặc từ chối", ly_do))
    actions = [build_action("lenh_cua_toi")]
    dang_cho = next((lenh for lenh in lenh_ds if lenh.get("status") in _LENH_CON_HIEU_LUC
                     and _ma(lenh.get("listingId"))), None)
    if dang_cho is not None:
        ma_khoan = _ma(dang_cho.get("listingId")) or ""
        actions.append(build_action("so_lenh", {"listingId": ma_khoan}, label=f"Sổ lệnh khoản #{ma_khoan}"))
    ids = [_ma_chu(lenh.get("orderReference")) for lenh in lenh_ds] + [_ma(lenh.get("listingId")) for lenh in lenh_ds]
    return _ket_qua(facts, cards=cards, actions=actions, ids=ids)


# ----------------------------------------------------------------- so_lenh


def _muc_gia(muc: list[dict[str, Any]]) -> list[dict[str, str]]:
    return [{"gia": _gia(item.get("pricePercent")), "so_note": so_nguyen(_so_dem(item.get("quantity"))),
             "so_lenh": so_nguyen(_so_dem(item.get("orderCount")))} for item in muc]


async def _so_lenh(client: FinoraApiClient, params: ListingIdParams) -> ToolResult:
    ma_khoan = str(params.listing_id)
    so = _obj(await client.get(ServiceName.INVESTMENT, _ORDER_BOOK.format(listing_id=params.listing_id)))
    ben_mua = _ds(so.get("bids"))[:_SO_MUC_GIA]
    ben_ban = _ds(so.get("asks"))[:_SO_MUC_GIA]
    gia_mua, gia_ban = so.get("bestBidPercent"), so.get("bestAskPercent")
    tham_chieu = so.get("referenceOutstanding")
    facts: dict[str, Any] = {
        "ma_khoan": ma_khoan,
        "ma_khoan_vay": _ma(so.get("loanId")) or "—",
        "hang": _hang(so.get("creditGrade")),
        "lai_suat": _lai(so.get("annualInterestRate")),
        "ky_han": _ky_han(so.get("termMonths")),
        "menh_gia_note": tien_vnd(_tien(so.get("noteDenomination"))),
        "du_no_tham_chieu_moi_note": (tien_vnd(_tien(tham_chieu)) if tham_chieu is not None
                                      else "Không còn Note lưu hành"),
        "no_xau": so.get("defaulted") is True,
        "gia_mua_cao_nhat": _gia(gia_mua),
        "gia_ban_thap_nhat": _gia(gia_ban),
        "chenh_lech_gia": (f"{phan_tram(_tien(gia_ban) - _tien(gia_mua))} dư nợ gốc"
                           if gia_mua is not None and gia_ban is not None else "Chưa có đủ hai phía"),
        "gia_khop_gan_nhat": _gia(so.get("lastTradePercent")),
        "khop_gan_nhat_luc": _thoi_gian(so.get("lastTradeAt")),
        "so_lan_khop_gan_day": so_nguyen(len(_ds(so.get("recentTrades")))),
        "ben_mua": _muc_gia(ben_mua),
        "ben_ban": _muc_gia(ben_ban),
        "cach_doc_gia": _CACH_DOC_GIA,
    }
    if facts["no_xau"]:
        # Câu cố định thay vì chép defaultWarning: facts chỉ nhận chữ do chính FINORA viết trong code này.
        facts["canh_bao"] = "Khoản vay gốc đang nợ xấu; Note có thể không thu hồi đủ gốc lãi."
    tong_quan = [
        ("Giá mua cao nhất", facts["gia_mua_cao_nhat"]),
        ("Giá bán thấp nhất", facts["gia_ban_thap_nhat"]),
        ("Khớp gần nhất", f"{facts['gia_khop_gan_nhat']} · {facts['khop_gan_nhat_luc']}"),
        ("Dư nợ tham chiếu mỗi Note", facts["du_no_tham_chieu_moi_note"]),
    ]
    if facts["no_xau"]:
        tong_quan.append(("Cảnh báo", facts["canh_bao"]))
    do_sau = [[_pt(mua.get("pricePercent")) if mua else "", so_nguyen(_so_dem(mua.get("quantity"))) if mua else "",
               _pt(ban.get("pricePercent")) if ban else "", so_nguyen(_so_dem(ban.get("quantity"))) if ban else ""]
              for mua, ban in zip_longest(ben_mua, ben_ban)]
    cards = [_the_kv(f"Sổ lệnh khoản #{ma_khoan}", tong_quan),
             _the_bang("5 mức giá mỗi phía", ["Giá mua", "SL mua", "Giá bán", "SL bán"], do_sau)]
    actions = [build_action("so_lenh", {"listingId": ma_khoan}, label=f"Mở sổ lệnh khoản #{ma_khoan}"),
               build_action("lenh_cua_toi")]
    return _ket_qua(facts, cards=cards, actions=actions, ids=[ma_khoan, _ma(so.get("loanId"))])


# ----------------------------------------------------------------- khoan_dang_goi_von


def _so_thap_phan(value: float) -> str:
    """12.5 -> "12.5", 12.0 -> "12": gửi đúng dạng BigDecimal Spring đọc được, không mang đuôi float."""
    return format(Decimal(str(value)).normalize(), "f")


def _khoa_han_dong(listing: dict[str, Any]) -> tuple[datetime, int]:
    """Hạn đóng sớm nhất lên trước; khoản thiếu hạn đóng xuống cuối; hòa thì theo mã khoản."""
    return (_thoi_diem(listing.get("fundingClosesAt")) or _XA_NHAT, _so_dem(listing.get("listingId")))


def _khoan(listing: dict[str, Any]) -> dict[str, Any]:
    return {
        "ma_khoan": _ma(listing.get("listingId")) or "—",
        "hang": _hang(listing.get("creditGrade")),
        "lai_suat": _lai(listing.get("annualInterestRate")),
        "ky_han": _ky_han(listing.get("termMonths")),
        "muc_dich": _muc_dich(listing.get("purpose")),
        "so_tien_goi": tien_vnd(_tien(listing.get("targetAmount"))),
        "con_thieu": tien_vnd(_tien(listing.get("remainingAmount"))),
        "tien_do": _pt(listing.get("fundedPercent")),
        "han_dong": _thoi_gian(listing.get("fundingClosesAt")),
    }


async def _khoan_dang_goi_von(client: FinoraApiClient, params: ListingFilterParams) -> ToolResult:
    query: dict[str, Any] = {"status": "OPEN", "page": 0, "size": _TRANG_LON}
    if params.grade:
        query["grade"] = params.grade
    if params.min_rate is not None:
        query["minRate"] = _so_thap_phan(params.min_rate)
    if params.max_term_months is not None:
        query["maxTermMonths"] = params.max_term_months
    listings, tong = _trang(await client.get(ServiceName.INVESTMENT, _LISTINGS, query))
    # API sắp theo lãi suất giảm dần; sắp lại toàn bộ trang theo hạn đóng rồi mới cắt, để danh sách
    # không ngầm là "10 khoản lãi cao nhất".
    hien = sorted(listings, key=_khoa_han_dong)[:_TOI_DA_DONG]
    facts: dict[str, Any] = {
        "ghi_chu": ("Danh sách trung lập: chỉ lọc theo tiêu chí bạn chọn và sắp theo hạn đóng gọi vốn gần "
                    "nhất; không xếp hạng hay gợi ý khoản nào tốt hơn."),
        "bo_loc": {
            "hang": params.grade or "Mọi hạng",
            "lai_toi_thieu": _lai(_so_thap_phan(params.min_rate)) if params.min_rate is not None else "Không đặt",
            "ky_han_toi_da": _ky_han(params.max_term_months) if params.max_term_months is not None else "Không đặt",
        },
        "tong_so_khoan_khop_loc": so_nguyen(tong),
        "so_khoan_liet_ke": so_nguyen(len(hien)),
        "khoan": [_khoan(listing) for listing in hien],
    }
    if tong > len(listings):
        facts["gioi_han"] = (f"Chỉ xét {so_nguyen(len(listings))} khoản đầu trong {so_nguyen(tong)} khoản khớp "
                             "bộ lọc; hãy thu hẹp bộ lọc để xem đủ.")
    cards = []
    if hien:
        cards.append(_the_bang(
            "Khoản đang gọi vốn (sắp theo hạn đóng)", ["Khoản", "Hạng", "Lãi/năm", "Kỳ hạn", "Còn thiếu", "Hạn đóng"],
            [[f"#{item['ma_khoan']}", item["hang"], _pt(listing.get("annualInterestRate")), item["ky_han"],
              item["con_thieu"], item["han_dong"]] for item, listing in zip(facts["khoan"], hien)],
        ))
    actions = [build_action("mo_khoan_san", {"loanId": item["ma_khoan"]}, label=f"Mở khoản #{item['ma_khoan']}")
               for item in facts["khoan"][:3] if item["ma_khoan"] != "—"]
    return _ket_qua(facts, cards=cards, actions=actions, ids=[_ma(listing.get("listingId")) for listing in listings])


# ----------------------------------------------------------------- chi_tiet_khoan


async def _chi_tiet_khoan(client: FinoraApiClient, params: ListingIdParams) -> ToolResult:
    ma_khoan = str(params.listing_id)
    listing, tien_do = await _gom(
        client.get(ServiceName.INVESTMENT, _LISTING.format(listing_id=params.listing_id)),
        client.get(ServiceName.INVESTMENT, _LISTING_PROGRESS.format(listing_id=params.listing_id)),
    )
    listing, tien_do = _obj(listing), _obj(tien_do)
    # Số tiền lấy từ /progress: cùng nguồn với thanh tiến độ trên app và có thêm số nhà đầu tư.
    ty_le = min(max(float(_tien(tien_do.get("fundedPercent"))), 0.0), 100.0)
    facts: dict[str, Any] = {
        "ma_khoan": ma_khoan,
        "ma_khoan_vay": _ma(listing.get("loanId")) or "—",
        "trang_thai": dich_trang_thai("listing", listing.get("status")),
        "hang": _hang(listing.get("creditGrade")),
        "lai_suat": _lai(listing.get("annualInterestRate")),
        "ky_han": _ky_han(listing.get("termMonths")),
        "cach_tra": _CACH_TRA.get(listing.get("repaymentMethod"), "—"),
        "muc_dich": _muc_dich(listing.get("purpose")),
        "so_tien_goi": tien_vnd(_tien(tien_do.get("targetAmount"))),
        "da_goi": tien_vnd(_tien(tien_do.get("committedAmount"))),
        "con_thieu": tien_vnd(_tien(tien_do.get("remainingAmount"))),
        "tien_do": _pt(tien_do.get("fundedPercent")),
        "so_nha_dau_tu": so_nguyen(_so_dem(tien_do.get("investorCount"))),
        "so_note_da_dat": so_nguyen(_so_dem(tien_do.get("committedNoteCount"))),
        "menh_gia_note": tien_vnd(_tien(listing.get("noteDenomination"))),
        "dau_tu_toi_thieu": tien_vnd(_tien(listing.get("minInvestmentAmount"))),
        "han_dong": _thoi_gian(tien_do.get("fundingClosesAt") or listing.get("fundingClosesAt")),
    }
    if _thoi_diem(tien_do.get("fullyFundedAt")):
        facts["du_von_luc"] = _thoi_gian(tien_do.get("fullyFundedAt"))
    if isinstance(listing.get("contractStatus"), str):
        facts["trang_thai_hop_dong"] = dich_trang_thai("contract", listing.get("contractStatus"))
    cards = [
        Card(kind="progress", title=f"Tiến độ gọi vốn khoản #{ma_khoan}", value=ty_le, max=100.0,
             label=f"Đã gọi {facts['da_goi']} / {facts['so_tien_goi']}"),
        _the_kv(f"Khoản #{ma_khoan}", [
            ("Trạng thái", facts["trang_thai"]),
            ("Hạng", facts["hang"]),
            ("Lãi suất", facts["lai_suat"]),
            ("Kỳ hạn", facts["ky_han"]),
            ("Cách trả", facts["cach_tra"]),
            ("Mục đích", facts["muc_dich"]),
            ("Nhà đầu tư đã góp", facts["so_nha_dau_tu"]),
            ("Hạn đóng gọi vốn", facts["han_dong"]),
            ("Mệnh giá Note", facts["menh_gia_note"]),
            ("Góp tối thiểu", facts["dau_tu_toi_thieu"]),
        ]),
    ]
    actions = [build_action("mo_khoan_san", {"loanId": ma_khoan}, label=f"Mở khoản #{ma_khoan}")]
    return _ket_qua(facts, cards=cards, actions=actions, ids=[ma_khoan, _ma(listing.get("loanId"))])


# ----------------------------------------------------------------- auto_invest


def _ly_do_bo_qua(ma: Any) -> str:
    return dich_ma_loi(ma) if isinstance(ma, str) and ma else "Không rõ lý do"


def _lan_xet(lan: dict[str, Any]) -> dict[str, Any]:
    item: dict[str, Any] = {
        "luc": _thoi_gian(lan.get("at")),
        "ma_khoan": _ma(lan.get("listingId")) or "—",
        "hang": _hang(lan.get("creditGrade")),
        "lai_suat": _lai(lan.get("annualInterestRate")),
        "ket_qua": "Đã đặt lệnh" if lan.get("outcome") == "MATCHED" else "Bỏ qua",
    }
    if lan.get("outcome") == "SKIPPED":
        item["ly_do"] = _ly_do_bo_qua(lan.get("reason"))
    if lan.get("amount") is not None:
        item["so_tien"] = tien_vnd(_tien(lan.get("amount")))
    return item


async def _auto_invest(client: FinoraApiClient, _params: NoParams) -> ToolResult:
    cau_hinh, lich_su = await _gom(
        client.get(ServiceName.INVESTMENT, _AUTO_INVEST),
        client.get(ServiceName.INVESTMENT, _AUTO_INVEST_MATCHES, {"limit": 20}),
    )
    cau_hinh, lich_su = _obj(cau_hinh), _ds(lich_su)
    hang_tho = cau_hinh.get("grades")
    hang = [g for g in hang_tho if isinstance(g, str) and g in _HANG] if isinstance(hang_tho, list) else []
    dang_bat = cau_hinh.get("enabled") is True
    bo_qua = [lan for lan in lich_su if lan.get("outcome") == "SKIPPED"]
    da_dat = [lan for lan in lich_su if lan.get("outcome") == "MATCHED"]
    dem = Counter(_ly_do_bo_qua(lan.get("reason")) for lan in bo_qua)
    facts: dict[str, Any] = {
        "trang_thai": "Đang bật" if dang_bat else "Đang tắt",
        "dang_bat": dang_bat,
        "hang_da_chon": ", ".join(hang) if hang else "Chưa chọn hạng nào",
        "lai_toi_thieu": _lai(cau_hinh.get("minAnnualRate")),
        "ky_han_toi_da": _ky_han(cau_hinh.get("maxTermMonths")),
        "so_tien_moi_khoan": tien_vnd(_tien(cau_hinh.get("amountPerLoan"))),
        "bat_tu": _thoi_gian(cau_hinh.get("enabledAt")),
        "quy_tac_xet": _QUY_TAC_AUTO_INVEST,
        "so_lan_xet_gan_day": so_nguyen(len(lich_su)),
        "so_lan_da_dat_lenh": so_nguyen(len(da_dat)),
        "so_lan_bo_qua": so_nguyen(len(bo_qua)),
        "ly_do_bo_qua": [{"ly_do": ly_do, "so_lan": so_nguyen(so_lan)} for ly_do, so_lan in dem.most_common(5)],
        "lich_su": [_lan_xet(lan) for lan in lich_su[:_TOI_DA_DONG]],
    }
    cards = [_the_kv("Cấu hình Auto-Invest", [
        ("Trạng thái", facts["trang_thai"]),
        ("Hạng", facts["hang_da_chon"]),
        ("Lãi tối thiểu", facts["lai_toi_thieu"]),
        ("Kỳ hạn tối đa", facts["ky_han_toi_da"]),
        ("Mỗi khoản", facts["so_tien_moi_khoan"]),
        ("Bật từ", facts["bat_tu"]),
    ])]
    if facts["lich_su"]:
        cards.append(_the_bang("Lần xét gần đây", ["Lúc", "Khoản", "Kết quả", "Lý do / số tiền"],
                               [[item["luc"], f"#{item['ma_khoan']}", item["ket_qua"],
                                 item.get("ly_do") or item.get("so_tien") or ""] for item in facts["lich_su"]]))
    ids = [_ma(lan.get("listingId")) for lan in lich_su] + [_ma_chu(lan.get("orderReference")) for lan in lich_su]
    return _ket_qua(facts, cards=cards, actions=[build_action("auto_invest")], ids=ids)


# ----------------------------------------------------------------- hop_dong_dau_tu


def _can_ky(hop_dong: dict[str, Any]) -> bool:
    """Phần của tôi còn chờ ký và hợp đồng còn trong giai đoạn ký (chưa hết hạn/từ chối/hiệu lực)."""
    return hop_dong.get("partyStatus") == "PENDING_SIGNATURE" and hop_dong.get("contractStatus") in _HD_CHO_KY


def _hop_dong(hop_dong: dict[str, Any]) -> dict[str, Any]:
    return {
        "ma_hop_dong": _ma_chu(hop_dong.get("contractNumber")) or "—",
        "trang_thai_hop_dong": dich_trang_thai("contract", hop_dong.get("contractStatus")),
        "phan_cua_ban": dich_trang_thai("contract", hop_dong.get("partyStatus")),
        "can_ky": _can_ky(hop_dong),
        "so_tien_cua_ban": tien_vnd(_tien(hop_dong.get("investorAmount"))),
        "ky_han": _ky_han(hop_dong.get("termMonths")),
        "so_phan_bo": so_nguyen(_so_dem(hop_dong.get("allocationCount"))),
        "ben_cho_vay_chua_ky": so_nguyen(_so_dem(hop_dong.get("remainingLenderSignatures"))),
        "han_ky": _thoi_gian(hop_dong.get("expiresAt")),
    }


async def _hop_dong_dau_tu(client: FinoraApiClient, _params: NoParams) -> ToolResult:
    ds = _ds(await client.get(ServiceName.LOAN, _INVESTOR_CONTRACTS))
    # Hợp đồng cần ký lên đầu, gần hạn trước: đó là việc người dùng phải làm trước khi hết hạn.
    sap_xep = sorted(ds, key=lambda hd: (not _can_ky(hd), _thoi_diem(hd.get("expiresAt")) or _XA_NHAT))
    hien = [_hop_dong(hd) for hd in sap_xep[:_TOI_DA_DONG]]
    so_can_ky = sum(1 for hd in ds if _can_ky(hd))
    facts: dict[str, Any] = {
        "so_hop_dong": so_nguyen(len(ds)),
        "so_can_ky": so_nguyen(so_can_ky),
        "co_can_ky": so_can_ky > 0,
        "hop_dong": hien,
    }
    cards = []
    if hien:
        cards.append(_the_bang("Hợp đồng đầu tư", ["Mã HĐ", "Số tiền", "Phần của bạn", "Hạn ký"],
                               [[item["ma_hop_dong"], item["so_tien_cua_ban"], item["phan_cua_ban"], item["han_ky"]]
                                for item in hien]))
    actions = [build_action("ky_hop_dong_dau_tu")] if so_can_ky else []
    return _ket_qua(facts, cards=cards, actions=actions, ids=[_ma_chu(hd.get("contractNumber")) for hd in ds])


# ----------------------------------------------------------------- thong_bao_gan_day


def _thong_bao(thong_bao: dict[str, Any]) -> dict[str, Any]:
    tham_chieu = thong_bao.get("businessReference")
    noi_dung = thong_bao.get("message") if isinstance(thong_bao.get("message"), str) else ""
    item: dict[str, Any] = {
        "loai": _LOAI_THONG_BAO.get(thong_bao.get("type"), "Thông báo khác"),
        "luc": _thoi_gian(thong_bao.get("occurredAt")),
        "chua_doc": thong_bao.get("unread") is True,
        "ma_khoan_vay": (tham_chieu if isinstance(tham_chieu, str) and tham_chieu.isdigit()
                         and len(tham_chieu) <= 19 else "—"),
    }
    if match := _RE_NHOM_NO.search(noi_dung):
        item["nhom_no"] = _nhom_no(int(match.group(1)))
    if match := _RE_SO_NGAY.search(noi_dung):
        item["so_ngay_qua_han"] = so_nguyen(int(match.group(1)))
    if match := _RE_SO_TIEN.search(noi_dung):
        item["so_tien"] = tien_vnd(_tien(match.group(1)))
    if match := _RE_DAO_HAN.search(noi_dung):
        item["ngay_dao_han_moi"] = _ngay(match.group(1))
    return item


async def _thong_bao_gan_day(client: FinoraApiClient, _params: NoParams) -> ToolResult:
    ds = _ds(await client.get(ServiceName.NOTIFICATION, _NOTIFICATIONS, {"limit": _TOI_DA_DONG}))
    items = [_thong_bao(thong_bao) for thong_bao in ds]
    facts: dict[str, Any] = {
        "so_thong_bao": so_nguyen(len(items)),
        "so_chua_doc": so_nguyen(sum(1 for item in items if item["chua_doc"])),
        "thong_bao": items,
    }
    if any("nhom_no" in item or "so_ngay_qua_han" in item for item in items):
        facts["giai_thich_nhom_no"] = _GIAI_THICH_NHOM_NO
    cards = []
    if ds:
        # Tiêu đề và nội dung chỉ hiện trên thẻ cho chính người nhận, không vào facts.
        cards.append(_the_bang("Thông báo gần đây", ["Lúc", "Tiêu đề", "Nội dung"],
                               [[item["luc"],
                                 thong_bao.get("title") if isinstance(thong_bao.get("title"), str) else "",
                                 thong_bao.get("message") if isinstance(thong_bao.get("message"), str) else ""]
                                for item, thong_bao in zip(items, ds)]))
    actions = [build_action("danh_muc")] if items else []
    return _ket_qua(facts, cards=cards, actions=actions, ids=[item["ma_khoan_vay"] for item in items
                                                              if item["ma_khoan_vay"] != "—"])


# ----------------------------------------------------------------- câu trả lời mẫu (không gọi LLM)


def _tra_loi_danh_muc(result: ToolResult) -> str:
    facts = result.facts
    if not facts["vi_the"] and not facts["co_von_cho_giai_ngan"]:
        return ("Danh mục của bạn chưa có khoản nào. Khi bạn góp vốn, số tiền chờ giải ngân và các khoản đang "
                "nắm sẽ hiện ở đây.")
    cau = [
        f"Bạn đang nắm {facts['so_khoan']} khoản vay: vốn đang đầu tư {facts['von_dang_dau_tu']} trong "
        f"{facts['so_note_dang_hoat_dong']} Note đang trả bình thường, vốn chờ giải ngân {facts['von_cho_giai_ngan']}.",
        f"Đã nhận {facts['tong_da_nhan']} gồm gốc {facts['goc_da_thu']} và lãi {facts['lai_da_nhan']}; "
        f"lãi suất bình quân theo dư nợ {facts['lai_suat_binh_quan']}.",
    ]
    if facts["co_no_xau"]:
        cau.append(f"Có {facts['so_khoan_no_xau']} khoản đang nợ xấu với dư nợ gốc {facts['du_no_khoan_no_xau']}, "
                   "tính riêng khỏi vốn đang đầu tư; Note của khoản nợ xấu có thể không thu hồi đủ gốc lãi.")
    if facts["co_qua_han"]:
        cau.append(f"{facts['so_khoan_qua_han']} khoản đang quá hạn, chi tiết ở bảng bên dưới.")
    return " ".join(cau)


def _tra_loi_tien_giu(result: ToolResult) -> str:
    facts = result.facts
    if not facts["co_tien_giu"] and not facts["chi_tiet"]:
        return f"Ví của bạn không có tiền nào đang bị giữ. Số dư khả dụng: {facts['so_du_kha_dung']}."
    cau = [f"Ví đang giữ {facts['tong_dang_giu']} (khả dụng {facts['so_du_kha_dung']})."]
    if facts["co_giu_goi_von"]:
        cau.append(f"{facts['giu_cho_goi_von']} giữ cho phần vốn đã góp vào khoản vay chưa phát hành Note: "
                   f"{facts['trong_do_dang_goi_von']} ở {facts['so_khoan_dang_goi_von']} khoản đang gọi vốn và "
                   f"{facts['trong_do_da_khoa_cho_giai_ngan']} đã khóa chờ giải ngân.")
    if facts["co_giu_lenh_mua"]:
        cau.append(f"{facts['giu_cho_lenh_mua']} giữ cho {facts['so_lenh_mua_dang_cho']} lệnh mua Note đang chờ "
                   "khớp; khớp ở giá thấp hơn thì phần giữ thừa trả lại ví khi lệnh kết thúc.")
    if facts["co_cho_xac_nhan"]:
        cau.append(f"{facts['dang_cho_vi_xac_nhan']} thuộc lệnh mua đang chờ ví xác nhận giữ tiền.")
    cau.append(facts["ghi_chu_chenh_lech"])
    return " ".join(cau)


def _tra_loi_lenh(result: ToolResult) -> str:
    lenh = result.facts["lenh"]
    if not lenh:
        return "Bạn chưa đặt lệnh nào trên chợ Notes nên chưa có lệnh để giải thích."
    cau = []
    ket_thuc = next((item for item in lenh if "ly_do_huy" in item or "ly_do_tu_choi" in item), None)
    if ket_thuc is not None:
        ly_do = ket_thuc.get("ly_do_tu_choi") or ket_thuc["ly_do_huy"]
        cau.append(f"Lệnh {ket_thuc['ma_lenh']} ({ket_thuc['chieu'].lower()} {ket_thuc['so_note_dat']} Note, giá "
                   f"{ket_thuc['gia']}) ở trạng thái {ket_thuc['trang_thai']}: {_cau(ly_do)}")
    dang_cho = next((item for item in lenh if item["con_hieu_luc"]), None)
    if dang_cho is not None:
        cau.append(f"Lệnh {dang_cho['ma_lenh']} ({dang_cho['chieu'].lower()}, giá {dang_cho['gia']}) đang chờ khớp, "
                   f"đã khớp {dang_cho['so_note_da_khop']}/{dang_cho['so_note_dat']} Note. Lệnh giới hạn chỉ khớp "
                   "khi bên kia có giá phù hợp: lệnh mua cần người bán chào bằng hoặc thấp hơn giá của bạn, lệnh "
                   "bán cần người mua trả bằng hoặc cao hơn. Mở sổ lệnh để xem giá đang chờ ở hai phía.")
    if not cau:
        return "Các lệnh gần đây của bạn đều đã khớp hết, không có lệnh nào đang chờ hay bị hủy."
    return " ".join(cau)


def _tra_loi_khoan(result: ToolResult) -> str:
    facts = result.facts
    return (f"Khoản #{facts['ma_khoan']} hạng {facts['hang']}, lãi {facts['lai_suat']}, kỳ hạn {facts['ky_han']}, "
            f"cách trả: {facts['cach_tra']}. Đã có {facts['so_nha_dau_tu']} nhà đầu tư góp {facts['da_goi']} trên "
            f"{facts['so_tien_goi']} ({facts['tien_do']}), còn thiếu {facts['con_thieu']}; hạn đóng gọi vốn "
            f"{facts['han_dong']}. Trạng thái: {facts['trang_thai']}. Mỗi Note mệnh giá {facts['menh_gia_note']}, "
            f"góp tối thiểu {facts['dau_tu_toi_thieu']}.")


def _tra_loi_auto_invest(result: ToolResult) -> str:
    facts = result.facts
    if not facts["lich_su"]:
        if not facts["dang_bat"]:
            return f"Auto-Invest của bạn đang tắt nên chưa xét khoản nào. {facts['quy_tac_xet']}"
        return (f"Auto-Invest đang bật từ {facts['bat_tu']} nhưng chưa xét khoản nào. Tiêu chí hiện tại: hạng "
                f"{facts['hang_da_chon']}, lãi từ {facts['lai_toi_thieu']}, kỳ hạn tối đa {facts['ky_han_toi_da']}. "
                f"{facts['quy_tac_xet']}")
    if not facts["ly_do_bo_qua"]:
        return (f"Trong {facts['so_lan_xet_gan_day']} lần xét gần đây, Auto-Invest không bỏ qua khoản nào; đã đặt "
                f"lệnh {facts['so_lan_da_dat_lenh']} lần.")
    ly_do = "; ".join(f"{item['ly_do'].rstrip('.')} ({item['so_lan']} lần)" for item in facts["ly_do_bo_qua"])
    cau = [f"Trong {facts['so_lan_xet_gan_day']} lần xét gần đây, Auto-Invest bỏ qua {facts['so_lan_bo_qua']} lần. "
           f"Lý do: {ly_do}."]
    gan_nhat = next((item for item in facts["lich_su"] if "ly_do" in item), None)
    if gan_nhat is not None:
        cau.append(f"Gần nhất là khoản #{gan_nhat['ma_khoan']} lúc {gan_nhat['luc']}: {_cau(gan_nhat['ly_do'])}")
    if not facts["dang_bat"]:
        cau.append("Hiện Auto-Invest đang tắt.")
    return " ".join(cau)


def _tra_loi_hop_dong(result: ToolResult) -> str:
    facts = result.facts
    if not facts["hop_dong"]:
        return "Bạn chưa có hợp đồng đầu tư nào."
    if not facts["co_can_ky"]:
        return (f"Bạn không có hợp đồng nào đang chờ bạn ký. Trạng thái {facts['so_hop_dong']} hợp đồng gần đây ở "
                "bảng bên dưới.")
    dau = next(item for item in facts["hop_dong"] if item["can_ky"])
    return (f"Bạn có {facts['so_can_ky']} hợp đồng đang chờ bạn ký. Gần hạn nhất là {dau['ma_hop_dong']} (phần của "
            f"bạn {dau['so_tien_cua_ban']}), hạn ký {dau['han_ky']}. Quá hạn ký thì hợp đồng hết hiệu lực; bấm "
            "nút bên dưới để mở màn ký.")


def _tra_loi_thong_bao(result: ToolResult) -> str:
    facts = result.facts
    ds = facts["thong_bao"]
    if not ds:
        return "Bạn chưa có thông báo nào."
    cau = [f"Bạn có {facts['so_thong_bao']} thông báo gần đây, {facts['so_chua_doc']} chưa đọc."]
    rui_ro = next((item for item in ds if "nhom_no" in item or "so_ngay_qua_han" in item), None)
    if rui_ro is None:
        cau.append(f"Mới nhất: {ds[0]['loai']} lúc {ds[0]['luc']}.")
        return " ".join(cau)
    chi_tiet = []
    if "nhom_no" in rui_ro:
        chi_tiet.append(rui_ro["nhom_no"])
    if "so_ngay_qua_han" in rui_ro:
        chi_tiet.append(f"quá hạn {rui_ro['so_ngay_qua_han']} ngày")
    cau.append(f"Thông báo lúc {rui_ro['luc']} về khoản vay #{rui_ro['ma_khoan_vay']}: {', '.join(chi_tiet)}.")
    cau.append(facts["giai_thich_nhom_no"])
    return " ".join(cau)


# ----------------------------------------------------------------- ba hằng export cho registry (Task 6)

INVESTOR_TOOLS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name="danh_muc_cua_toi",
        toolset=Toolset.INVESTOR,
        description=("Tóm tắt danh mục đầu tư của chính người hỏi: vốn chờ giải ngân, vốn đang đầu tư, gốc và "
                     "lãi đã nhận, lãi suất bình quân theo dư nợ, các khoản đang nắm kèm nhóm nợ, quá hạn, nợ xấu. "
                     "Chỉ mô tả số liệu, không đánh giá hay khuyến nghị."),
        params_model=NoParams,
        handler=_danh_muc_cua_toi,
        lane=Lane.USER_DATA,
        routes=(RouteRule("GET", ServiceName.INVESTMENT, _PORTFOLIO),),
    ),
    ToolSpec(
        name="tien_dang_giu",
        toolset=Toolset.INVESTOR,
        description=("Giải thích tiền trong ví đang bị giữ ở đâu: phần giữ cho khoản đang gọi vốn hoặc đã khóa "
                     "chờ giải ngân, phần giữ cho lệnh mua Note đang chờ khớp, và phần chưa đối chiếu được."),
        params_model=NoParams,
        handler=_tien_dang_giu,
        lane=Lane.USER_DATA,
        routes=(
            RouteRule("GET", ServiceName.PAYMENT, _WALLET),
            RouteRule("GET", ServiceName.INVESTMENT, _PORTFOLIO),
            RouteRule("GET", ServiceName.INVESTMENT, _COMMITMENTS),
            RouteRule("GET", ServiceName.INVESTMENT, _MY_ORDERS),
        ),
    ),
    ToolSpec(
        name="lenh_cua_toi",
        toolset=Toolset.INVESTOR,
        description=("Các lệnh mua/bán Note gần nhất của người hỏi trên chợ Notes: giá, số Note đã khớp, trạng "
                     "thái, lý do bị hủy hoặc bị từ chối (đã dịch), tiền giữ của lệnh mua. Dùng khi hỏi vì sao lệnh "
                     "chưa khớp hoặc bị hủy."),
        params_model=NoParams,
        handler=_lenh_cua_toi,
        lane=Lane.USER_DATA,
        routes=(RouteRule("GET", ServiceName.INVESTMENT, _MY_ORDERS),),
    ),
    ToolSpec(
        name="so_lenh",
        toolset=Toolset.INVESTOR,
        description=("Ảnh chụp sổ lệnh của một đợt gọi vốn (listingId): giá mua cao nhất, giá bán thấp nhất, lần "
                     "khớp gần nhất, 5 mức giá mỗi phía. Giá tính theo % dư nợ gốc còn lại của Note. Chỉ mô tả, "
                     "không gợi ý giá đặt."),
        params_model=ListingIdParams,
        handler=_so_lenh,
        lane=Lane.USER_DATA,
        routes=(RouteRule("GET", ServiceName.INVESTMENT, r"/api/v1/investments/order-books/\d+"),),
    ),
    ToolSpec(
        name="khoan_dang_goi_von",
        toolset=Toolset.INVESTOR,
        description=("Liệt kê khoản vay đang gọi vốn theo tiêu chí người hỏi TỰ nêu (hạng, lãi tối thiểu, kỳ hạn "
                     "tối đa), sắp theo hạn đóng gọi vốn gần nhất. Danh sách trung lập: không xếp hạng, không chọn "
                     "hộ khoản nào. Không tự thêm tiêu chí người hỏi không nêu."),
        params_model=ListingFilterParams,
        handler=_khoan_dang_goi_von,
        lane=Lane.USER_DATA,
        routes=(RouteRule("GET", ServiceName.INVESTMENT, _LISTINGS),),
    ),
    ToolSpec(
        name="chi_tiet_khoan",
        toolset=Toolset.INVESTOR,
        description=("Chi tiết một khoản trên sàn theo listingId (số trên màn 'Khoản vay #'): hạng, lãi, kỳ hạn, "
                     "cách trả, tiến độ gọi vốn, số nhà đầu tư, hạn đóng gọi vốn, mệnh giá Note, mức góp tối thiểu."),
        params_model=ListingIdParams,
        handler=_chi_tiet_khoan,
        lane=Lane.USER_DATA,
        routes=(
            RouteRule("GET", ServiceName.INVESTMENT, r"/api/v1/market/listings/\d+"),
            RouteRule("GET", ServiceName.INVESTMENT, r"/api/v1/market/listings/\d+/progress"),
        ),
    ),
    ToolSpec(
        name="auto_invest",
        toolset=Toolset.INVESTOR,
        description=("Cấu hình Auto-Invest của người hỏi và các lần xét gần nhất: khoản nào đã đặt lệnh, khoản nào "
                     "bị bỏ qua và lý do (đã dịch)."),
        params_model=NoParams,
        handler=_auto_invest,
        lane=Lane.USER_DATA,
        routes=(
            RouteRule("GET", ServiceName.INVESTMENT, _AUTO_INVEST),
            RouteRule("GET", ServiceName.INVESTMENT, _AUTO_INVEST_MATCHES),
        ),
    ),
    ToolSpec(
        name="hop_dong_dau_tu",
        toolset=Toolset.INVESTOR,
        description=("Hợp đồng cho vay mà người hỏi là bên cho vay: trạng thái hợp đồng, phần của người hỏi đã ký "
                     "chưa, số tiền, hạn ký. Dùng khi hỏi có hợp đồng nào cần ký."),
        params_model=NoParams,
        handler=_hop_dong_dau_tu,
        lane=Lane.USER_DATA,
        routes=(RouteRule("GET", ServiceName.LOAN, _INVESTOR_CONTRACTS),),
    ),
    ToolSpec(
        name="thong_bao_gan_day",
        toolset=Toolset.INVESTOR,
        description=("10 thông báo gần nhất của nhà đầu tư (nhận tiền trả nợ, quá hạn, nhóm nợ, tất toán, cơ cấu) "
                     "kèm nhóm nợ, số ngày quá hạn và số tiền rút ra từ thông báo."),
        params_model=NoParams,
        handler=_thong_bao_gan_day,
        lane=Lane.USER_DATA,
        routes=(RouteRule("GET", ServiceName.NOTIFICATION, _NOTIFICATIONS),),
    ),
)

INVESTOR_SUGGESTIONS: tuple[SuggestionDef, ...] = (
    SuggestionDef(
        id="investor.portfolio.summary",
        label="Tóm tắt danh mục của tôi",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"Home", "Portfolio"}),
        tool="danh_muc_cua_toi",
        params_from_context={},
        render=_tra_loi_danh_muc,
        # "quá hạn", "nợ xấu" thuộc danh mục (bảng khoản đang nắm có tình trạng từng khoản), không thuộc thông báo
        # (fix-2: "Khoản nào của tôi đang quá hạn?" từng bị đưa sang công cụ thông báo).
        keywords=("danh mục", "tóm tắt danh mục", "đang đầu tư", "lãi đã nhận", "tiền lãi", "lợi nhuận",
                  "quá hạn", "nợ xấu", "nhóm nợ", "dpd"),
    ),
    SuggestionDef(
        id="investor.money.held",
        label="Tiền của tôi đang bị giữ ở đâu?",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"Home", "Portfolio", "MyBookOrders"}),
        tool="tien_dang_giu",
        params_from_context={},
        render=_tra_loi_tien_giu,
        keywords=("bị giữ", "đang giữ", "tiền giữ", "tạm giữ", "phong tỏa", "không rút được"),
    ),
    SuggestionDef(
        id="investor.order.why_not_matched",
        label="Vì sao lệnh của tôi chưa khớp?",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"MyBookOrders", "OrderBook"}),
        tool="lenh_cua_toi",
        params_from_context={},
        render=_tra_loi_lenh,
        keywords=("chưa khớp", "không khớp", "lệnh bị hủy", "lệnh bị huỷ", "lệnh của tôi", "bị từ chối"),
    ),
    SuggestionDef(
        id="investor.autoinvest.skipped",
        label="Vì sao Auto-Invest bỏ qua khoản vay?",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"AutoInvest"}),
        tool="auto_invest",
        params_from_context={},
        render=_tra_loi_auto_invest,
        keywords=("auto-invest", "auto invest", "tự động đầu tư", "tự đầu tư", "bỏ qua khoản"),
    ),
    SuggestionDef(
        id="investor.contract.to_sign",
        label="Tôi có hợp đồng nào cần ký?",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"Home"}),
        tool="hop_dong_dau_tu",
        params_from_context={},
        render=_tra_loi_hop_dong,
        keywords=("hợp đồng", "cần ký", "chưa ký", "ký hợp đồng", "hạn ký"),
    ),
    SuggestionDef(
        id="investor.listing.explain",
        label="Giải thích khoản vay này",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"LoanDetail"}),
        tool="chi_tiet_khoan",
        params_from_context={"listingId": "loanId"},
        render=_tra_loi_khoan,
        keywords=("khoản này", "khoản vay này", "giải thích khoản", "tiến độ gọi vốn", "hạn đóng"),
    ),
    SuggestionDef(
        id="investor.notifications.explain",
        label="Giải thích thông báo gần đây",
        toolset=Toolset.INVESTOR,
        screens=frozenset({"Home"}),
        tool="thong_bao_gan_day",
        params_from_context={},
        render=_tra_loi_thong_bao,
        keywords=("thông báo", "tin báo", "báo gì"),
    ),
)

# Màn hình → công cụ lấy dữ liệu của màn đó (đường B của orchestrator). Màn LoanDetail của app truyền
# `loanId = String(listingId)` (finora-mobile/src/features/market/mapper.ts), nên ánh xạ sang listingId.
INVESTOR_SCREEN_TOOLS: dict[str, ScreenTool] = {
    "LoanDetail": ("chi_tiet_khoan", {"listingId": "loanId"}),
    "OrderBook": ("so_lenh", {"listingId": "listingId"}),
    "MyBookOrders": ("lenh_cua_toi", {}),
    "AutoInvest": ("auto_invest", {}),
    "Portfolio": ("danh_muc_cua_toi", {}),
}
