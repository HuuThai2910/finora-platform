"""
Công cụ chỉ đọc của Trợ lý FINORA cho quản trị viên (surface ``web``, ``Toolset.ADMIN``).

Mỗi công cụ gọi thẳng service sở hữu dữ liệu bằng token của chính admin (relay), nên quyền vẫn do
service đích kiểm: ``/api/v1/admin/**`` của Loan, ``user:admin:read_all`` của User,
``/api/v1/investments/admin/**`` của Investment. Bot không có tài khoản dịch vụ riêng.

Ba nguyên tắc chạy xuyên file:

* **Không chấm lại hồ sơ (LEGAL-AI-01).** Tóm tắt hồ sơ chỉ đọc bản chấm đã lưu qua ``/review`` và
  ``/explanation`` của Loan; không gọi ``/api/v1/ai/credit/explain``. Chấm lại có thể ra số khác khi
  mô hình hay bộ luật đã đổi, và khi đó câu trả lời không còn là bằng chứng cho quyết định đã ra.
* **Dữ kiện gửi LLM đi qua allowlist.** Hàm dựng ``facts`` chỉ chép trường được liệt kê; mọi trường
  khác của response (``borrowerId``, ``purposeDetail``, ``actorId``, ``reasonDetail``, SHAP, gợi ý cho
  người vay, giá trị đầu vào của từng luật) bị bỏ vì không được đọc tới. Thu nhập, nợ và DTI chỉ đi
  dưới dạng khoảng ("10–20 triệu/tháng") để LLM đủ ý mà không cầm con số khai báo thật.
* **Làn dữ liệu do code quyết định (spec mục 7.2).** Số tổng hợp và chính sách là ``PUBLIC``; danh
  sách và tóm tắt hồ sơ là ``USER_DATA`` dù đã bỏ định danh.

Màn web có ngữ cảnh (Task 18 gửi đúng các tên này trong ``context.screen``):

* ``Dashboard``  — ``/dashboard``; không có params → ``kpi_khoan_vay``
* ``LoanList``   — ``/loans``; params ``status`` khi đang lọc → ``danh_sach_ho_so``
* ``LoanReview`` — ``/loans/:applicationNumber/review``; params ``applicationNumber`` → ``tom_tat_ho_so``
* ``Operations`` — ``/loans/operations``, ``/loans/overdue``, ``/reconciliation``; không có params → ``viec_can_lam``
"""

from __future__ import annotations

import asyncio
import re
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from itertools import pairwise
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel

from app.schemas.assistant import Card, CardItem, Citation
from app.services.assistant.deeplinks import build_action
from app.services.assistant.formatting import ngay_gio_vn, ngay_vn, phan_tram, so_nguyen, tien_vnd
from app.services.assistant.glossary import dich_ly_do, dich_trang_thai
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

# ── Hằng số ───────────────────────────────────────────────────────────────────

# Việt Nam không đổi giờ theo mùa nên lệch cố định +7 là đúng, và không phụ thuộc gói tzdata trên Windows.
GIO_VIET_NAM = timezone(timedelta(hours=7))

# Mã hồ sơ do Loan sinh: "LA-" + 20 ký tự hex in hoa (ApplicationNumberGenerator); dữ liệu mẫu có dạng
# "LA-TEST-001". Tối đa 30 ký tự như @Size(max = 30) của controller. Regex chặn model chèn "/" hay ".."
# vào đường dẫn gọi Loan.
MA_HO_SO_REGEX = r"^LA-[A-Z0-9-]{1,27}$"
_MA_HO_SO_TRONG_PATH = r"LA-[A-Z0-9-]{1,27}"
_MA_HO_SO = re.compile(MA_HO_SO_REGEX)

# Mã luật, mã lý do, phiên bản chính sách: chỉ chữ in hoa, số, gạch dưới. Giá trị không khớp bị bỏ để
# không chuỗi tự do nào (ghi chú, lời người vay) lọt vào dữ kiện qua một trường "mã".
_MA_KY_THUAT = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
# Tên hạng theo ràng buộc GradeConfig của config_router.
_TEN_HANG = re.compile(r"^[A-Z][A-Z0-9+-]{0,7}$")

SO_NGAY_MAC_DINH = 30           # như StatisticsPeriod.DEFAULT_DAYS của finora-common
SO_NGAY_THONG_KE_TOI_DA = 366   # một năm theo ngày; khớp MAX_BUCKETS của API ở cột DAY
SO_HO_SO_MOI_TRANG = 20
SO_COT_GUI_LLM = 12             # chuỗi dài chỉ gửi 12 cột cuối để dữ kiện không vượt ~2.000 token
SO_NUT_TOI_DA = 4
KHONG_LAY_DUOC = "không lấy được"

TrangThaiHoSo = Literal[
    "SUBMITTED",
    "ELIGIBILITY_PENDING",
    "SCORING",
    "SCORING_RETRY_PENDING",
    "PENDING_REVIEW",
    "APPROVED",
    "REJECTED",
    "WITHDRAWN",
]
CotThoiGian = Literal["DAY", "WEEK", "MONTH"]

_DO_RONG_COT: dict[str, str] = {"DAY": "ngày", "WEEK": "tuần", "MONTH": "tháng"}

# Các bảng nhỏ dưới đây không nằm trong glossary vì chỉ màn quản trị dùng.
_NGUON_QUYET_DINH = {"AI_POLICY": "Tự động theo chính sách chấm điểm", "ADMIN": "Thẩm định viên"}
_TRANG_THAI_CHAM = {
    "PENDING": "Chờ chấm",
    "PROCESSING": "Đang chấm",
    "RETRY_PENDING": "Chờ chấm lại",
    "SUCCEEDED": "Đã chấm xong",
    "FAILED": "Chấm điểm lỗi",
}
# Nhãn và khoảng ngày lấy đúng COLLECTION_STAGES của web (cùng ranh giới CollectionStage của Loan).
_MUC_THU_HOI = {
    "EARLY_REMINDER": "Nhắc sớm (1–9 ngày)",
    "ATTENTION": "Cần chú ý (10–90 ngày)",
    "NPL": "Nợ xấu (91–180 ngày)",
    "INTENSIVE": "Thu hồi tăng cường (181–360 ngày)",
    "LOSS": "Có nguy cơ mất vốn (trên 360 ngày)",
}
_VAI_TRO = {"BORROWER": "Người vay", "INVESTOR": "Nhà đầu tư", "ADMIN": "Quản trị"}

# ── Đọc JSON an toàn và định dạng ─────────────────────────────────────────────


def _so(value: Any) -> Decimal | None:
    """Số JSON → Decimal qua ``str`` để không mang sai số nhị phân của float vào phép cộng tiền."""
    if value is None or isinstance(value, bool):
        return None
    try:
        so = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return so if so.is_finite() else None


def _so_nguyen_json(value: Any) -> int:
    """Số đếm trong JSON; thiếu khóa coi như 0, giống ``countIn`` của dashboard web (summary có thể bỏ khóa 0)."""
    return value if isinstance(value, int) and not isinstance(value, bool) else 0


def _dem(value: Any) -> str:
    """Số đếm đã định dạng; thiếu hẳn thì "—" để không bịa ra số 0 cho một chỉ số không có."""
    if isinstance(value, int) and not isinstance(value, bool):
        return so_nguyen(value)
    return "—"


def _diem(value: Any) -> str:
    """Điểm/trọng số kiểu Việt Nam, bỏ số 0 thừa: 70.5 → "70,5", 70.0 → "70", 0.85 → "0,85"."""
    so = _so(value)
    if so is None:
        return "—"
    gon = so.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP).normalize()
    return format(gon, "f").replace(".", ",")


def _thang(value: Any) -> str:
    return f"{value} tháng" if isinstance(value, int) and not isinstance(value, bool) else "—"


def _ty_le(value: Any, khi_rong: str) -> str:
    """Phần trăm đã có sẵn trong API (``nplRatioPercent``, ``fillRatePercent``); null có nghĩa riêng nên phải nói rõ."""
    so = _so(value)
    return phan_tram(so) if so is not None else khi_rong


def _pd(value: Any) -> str:
    """Xác suất vỡ nợ 0–1 của mô hình → phần trăm (0.28 → "28%")."""
    so = _so(value)
    return phan_tram(so * 100) if so is not None else "—"


def _lay(data: Any, *khoa: str) -> Any:
    for ten in khoa:
        if not isinstance(data, Mapping):
            return None
        data = data.get(ten)
    return data


def _bang(data: Any, *khoa: str) -> Mapping[str, Any]:
    gia_tri = _lay(data, *khoa)
    return gia_tri if isinstance(gia_tri, Mapping) else {}


def _danh_sach(data: Any, *khoa: str) -> list[Mapping[str, Any]]:
    gia_tri = _lay(data, *khoa)
    if not isinstance(gia_tri, list):
        return []
    return [phan_tu for phan_tu in gia_tri if isinstance(phan_tu, Mapping)]


def _doi_tuong(data: Any) -> Mapping[str, Any]:
    """Response phải là object JSON; khác đi là service trả sai hợp đồng, không đoán tiếp."""
    if not isinstance(data, Mapping):
        raise ToolError("DU_LIEU_KHONG_HOP_LE", "Dữ liệu trả về không đúng định dạng, thử lại sau.")
    return data


def _nhan(bang: Mapping[str, str], ma: Any) -> str:
    if not isinstance(ma, str) or not ma:
        return "—"
    return bang.get(ma, f"{ma} (chưa có diễn giải)")


def _dem_theo(ban_do: Any, dich: Callable[[str], str]) -> dict[str, str]:
    """Bản đồ đếm ``{MA: so}`` → ``{nhãn tiếng Việt: "số"}``, bỏ mục bằng 0.

    API thống kê trả đủ mọi giá trị enum kể cả 0 để web vẽ ổn định; gửi cả mục 0 cho LLM chỉ tốn token.
    """
    if not isinstance(ban_do, Mapping):
        return {}
    return {
        dich(str(ma)): so_nguyen(so)
        for ma, so in ban_do.items()
        if isinstance(so, int) and not isinstance(so, bool) and so > 0
    }


def _dem_trong(ban_do: Mapping[str, Any], khoa: str) -> str:
    return so_nguyen(_so_nguyen_json(ban_do.get(khoa)))


def _ma_ly_do(chuoi: Any) -> list[str]:
    """``rejectionReason`` của Loan là các mã nối bằng ", " (CreditScoringStateService)."""
    if not isinstance(chuoi, str):
        return []
    return [ma for ma in (phan.strip() for phan in chuoi.split(",")) if _MA_KY_THUAT.fullmatch(ma)]


def _ten_hang(ma: Any) -> str:
    if ma == "UNGRADED":
        return "Chưa xếp hạng"
    return f"Hạng {ma}" if isinstance(ma, str) and _TEN_HANG.fullmatch(ma) else "—"


def _hom_nay() -> str:
    return datetime.now(GIO_VIET_NAM).date().isoformat()


def _trich_dan_chinh_sach(phien_ban: str) -> Citation:
    """Căn cứ loại CHÍNH SÁCH cho câu trả lời về ngưỡng/hạng.

    Nhãn ghi rõ "không phải quy định pháp luật" để không gây nhầm lẫn chính sách nội bộ với luật
    (LAW-CONSUMER-2023).
    """
    return Citation(
        id="CS-THAM-DINH-01",
        kind="CHINH_SACH",
        label=f"Chính sách thẩm định FINORA {phien_ban} (không phải quy định pháp luật)",
        url=None,
        checked_at=_hom_nay(),
    )


# ── Khoảng thu nhập / nợ / DTI ────────────────────────────────────────────────

_TRIEU = Decimal(1_000_000)
_MOC_THU_NHAP = (5, 10, 20, 35, 50, 100)   # triệu đồng/tháng
_MOC_NO_THANG = (2, 5, 10, 20)             # triệu đồng/tháng
_MOC_DTI = (10, 20, 35, 50)                # phần trăm


def _khoang(gia_tri: Decimal, moc: tuple[int, ...], don_vi: str) -> str:
    """Xếp giá trị vào khoảng [mốc dưới, mốc trên): 10.000.000 rơi vào "10–20", không vào "5–10"."""
    if gia_tri < moc[0]:
        return f"dưới {moc[0]}{don_vi}"
    for thap, cao in pairwise(moc):
        if gia_tri < cao:
            return f"{thap}–{cao}{don_vi}"
    return f"từ {moc[-1]}{don_vi}"


def khoang_thu_nhap(gia_tri: Any) -> str:
    """Thu nhập tháng tự khai → khoảng. Con số thật là dữ liệu cá nhân, LLM chỉ cần mức để nhận xét."""
    so = _so(gia_tri)
    if so is None or so < 0:
        return "không có dữ liệu"
    return _khoang(so / _TRIEU, _MOC_THU_NHAP, " triệu/tháng")


def khoang_no_thang(gia_tri: Any) -> str:
    """Nghĩa vụ trả nợ hằng tháng → khoảng; 0 nói rõ là không có nợ thay vì "dưới 2 triệu"."""
    so = _so(gia_tri)
    if so is None or so < 0:
        return "không có dữ liệu"
    if so == 0:
        return "không có nợ phải trả hằng tháng"
    return _khoang(so / _TRIEU, _MOC_NO_THANG, " triệu/tháng")


def khoang_dti(gia_tri: Any) -> str:
    """DTI theo phần trăm (Loan lưu ``dtiSnapshot`` = nợ tháng / thu nhập tháng × 100) → khoảng."""
    so = _so(gia_tri)
    if so is None or so < 0:
        return "không có dữ liệu"
    return _khoang(so, _MOC_DTI, "%")


# ── Tham số công cụ ───────────────────────────────────────────────────────────


class _ThamSo(BaseModel):
    """Tên JSON camelCase đúng như LLM thấy (fromDate, applicationNumber); tham số lạ là lỗi ngay."""

    model_config = ConfigDict(extra="forbid", alias_generator=to_camel, populate_by_name=True)


class KpiTheoThoiGianParams(_ThamSo):
    from_date: date | None = Field(
        default=None, description="Ngày bắt đầu YYYY-MM-DD. Bỏ trống: 30 ngày gần nhất."
    )
    to_date: date | None = Field(
        default=None, description="Ngày kết thúc YYYY-MM-DD. Bỏ trống: hôm nay theo giờ Việt Nam."
    )
    bucket: CotThoiGian = Field(default="DAY", description="Độ rộng mỗi cột: DAY, WEEK hoặc MONTH.")

    @model_validator(mode="after")
    def _dien_mac_dinh_va_kiem_khoang(self) -> KpiTheoThoiGianParams:
        """Điền mặc định rồi kiểm khoảng trước khi gọi Loan.

        Lỗi khoảng ngày thành lỗi tham số (LLM được sửa một lần) thay vì 400 từ service.
        """
        den = self.to_date or datetime.now(GIO_VIET_NAM).date()
        tu = self.from_date or den - timedelta(days=SO_NGAY_MAC_DINH - 1)
        if tu > den:
            raise ValueError("fromDate phải trước hoặc bằng toDate")
        if (den - tu).days + 1 > SO_NGAY_THONG_KE_TOI_DA:
            raise ValueError(f"Khoảng thống kê tối đa {SO_NGAY_THONG_KE_TOI_DA} ngày")
        self.from_date, self.to_date = tu, den
        return self

    def khoang(self) -> tuple[date, date]:
        """Khoảng đã điền mặc định; validator bảo đảm hai đầu đều có giá trị."""
        if self.from_date is None or self.to_date is None:
            raise ValueError("Khoảng thống kê chưa được điền mặc định")
        return self.from_date, self.to_date


class DanhSachHoSoParams(_ThamSo):
    status: TrangThaiHoSo = Field(
        default="PENDING_REVIEW",
        description="Trạng thái hồ sơ cần liệt kê; mặc định PENDING_REVIEW (chờ thẩm định).",
    )


class TomTatHoSoParams(_ThamSo):
    application_number: str = Field(
        pattern=MA_HO_SO_REGEX, description="Mã hồ sơ, ví dụ LA-7FA66C3814FE443887DB."
    )


# ── kpi_khoan_vay ─────────────────────────────────────────────────────────────


async def _kpi_khoan_vay(client: FinoraApiClient, params: NoParams) -> ToolResult:
    """Ảnh chụp danh mục cho vay từ ``GET /api/v1/admin/loan-statistics/summary`` (STATS-001)."""
    data = _doi_tuong(await client.get(ServiceName.LOAN, "/api/v1/admin/loan-statistics/summary"))
    ho_so = _bang(data, "applications")
    danh_muc = _bang(data, "portfolio")
    thu_hoi = _bang(data, "collections")
    theo_trang_thai = _bang(ho_so, "byStatus")

    nhom_no = [
        {
            "nhom": f"Nhóm {_so_nguyen_json(dong.get('debtGroup'))}",
            "so_khoan": _dem(dong.get("loans")),
            "du_no_goc": tien_vnd(dong.get("principalOutstanding")),
            "qua_han": tien_vnd(dong.get("overdueAmount")),
        }
        for dong in _danh_sach(danh_muc, "byDebtGroup")
    ]
    theo_hang = {
        _ten_hang(dong.get("grade")): (
            f"{_dem(dong.get('loans'))} khoản, dư nợ gốc {tien_vnd(dong.get('principalOutstanding'))}"
        )
        for dong in _danh_sach(danh_muc, "byCreditGrade")
        if _so_nguyen_json(dong.get("loans")) > 0
    }
    san_pham = sorted(
        _danh_sach(danh_muc, "byProduct"),
        key=lambda dong: _so(dong.get("principalOutstanding")) or Decimal(0),
        reverse=True,
    )[:5]
    ty_le_no_xau = _ty_le(danh_muc.get("nplRatioPercent"), "chưa tính được (dư nợ gốc bằng 0)")

    facts: dict[str, Any] = {
        "thoi_diem": ngay_gio_vn(data.get("asOf")),
        "ho_so": {
            "tong": _dem(ho_so.get("total")),
            "cho_tham_dinh": _dem_trong(theo_trang_thai, "PENDING_REVIEW"),
            "cho_cham_lai": _dem_trong(theo_trang_thai, "SCORING_RETRY_PENDING"),
            "theo_trang_thai": _dem_theo(theo_trang_thai, lambda ma: dich_trang_thai("application", ma)),
            "theo_goi_von": _dem_theo(ho_so.get("byFundingStatus"), lambda ma: dich_trang_thai("funding", ma)),
        },
        "danh_muc": {
            "khoan_con_du_no": _dem(danh_muc.get("outstandingLoans")),
            "du_no_goc": tien_vnd(danh_muc.get("principalOutstanding")),
            "tong_du_no": tien_vnd(danh_muc.get("totalOutstanding")),
            "so_tien_qua_han": tien_vnd(danh_muc.get("overdueAmount")),
            "du_no_goc_no_xau": tien_vnd(danh_muc.get("nplPrincipalOutstanding")),
            "ty_le_no_xau": ty_le_no_xau,
            "theo_trang_thai_khoan_vay": _dem_theo(
                danh_muc.get("loansByStatus"), lambda ma: dich_trang_thai("loan", ma)
            ),
            "theo_nhom_no": nhom_no,
            "theo_hang": theo_hang,
            "theo_san_pham": [
                {
                    "san_pham": str(dong.get("productName") or dong.get("productCode") or "—")[:60],
                    "khoan_con_du_no": _dem(dong.get("outstandingLoans")),
                    "du_no_goc": tien_vnd(dong.get("principalOutstanding")),
                    "ty_le_no_xau": _ty_le(dong.get("nplRatioPercent"), "chưa tính được"),
                }
                for dong in san_pham
            ],
        },
        "thu_hoi": {
            "dang_mo": _dem(thu_hoi.get("openCases")),
            "theo_muc": _dem_theo(thu_hoi.get("openByStage"), lambda ma: _nhan(_MUC_THU_HOI, ma)),
        },
        "co_cau_cho_duyet": _dem_trong(_bang(data, "reschedules", "byStatus"), "PENDING_REVIEW"),
        "su_co_doi_soat_mo": _dem_trong(_bang(data, "reconciliationIncidents", "byStatus"), "OPEN"),
        "cham_diem": {
            "da_cham": _dem(_lay(data, "creditScores", "assessed")),
            "theo_hang": _dem_theo(_lay(data, "creditScores", "byGrade"), _ten_hang),
        },
        "dinh_nghia": (
            "Nợ xấu là dư nợ gốc của các khoản thuộc nhóm nợ 3 đến 5 (quá hạn trên 90 ngày); "
            "tỷ lệ nợ xấu = dư nợ gốc nợ xấu / dư nợ gốc."
        ),
    }
    so_khoan_cu = _so_nguyen_json(danh_muc.get("staleProjections"))
    if so_khoan_cu > 0:
        facts["canh_bao"] = (
            f"{so_nguyen(so_khoan_cu)} khoản vay có số liệu đồng bộ từ hệ thống lõi có thể đã cũ."
        )

    the_tong = Card(
        kind="kv",
        title="Danh mục cho vay",
        items=[
            CardItem(label="Khoản còn dư nợ", value=facts["danh_muc"]["khoan_con_du_no"]),
            CardItem(label="Dư nợ gốc", value=facts["danh_muc"]["du_no_goc"]),
            CardItem(label="Quá hạn", value=facts["danh_muc"]["so_tien_qua_han"]),
            CardItem(label="Tỷ lệ nợ xấu", value=ty_le_no_xau),
            CardItem(label="Hồ sơ chờ thẩm định", value=facts["ho_so"]["cho_tham_dinh"]),
            CardItem(label="Thu hồi đang mở", value=facts["thu_hoi"]["dang_mo"]),
        ],
    )
    the_nhom_no = Card(
        kind="table",
        title="Dư nợ theo nhóm nợ",
        columns=["Nhóm", "Số khoản", "Dư nợ gốc", "Quá hạn"],
        rows=[[d["nhom"], d["so_khoan"], d["du_no_goc"], d["qua_han"]] for d in nhom_no],
    )
    return ToolResult(
        facts=facts,
        cards=[the_tong, the_nhom_no],
        actions=[build_action("trang_tong_quan")],
    )


# ── kpi_theo_thoi_gian ────────────────────────────────────────────────────────


def _tong_tien(diem: list[Mapping[str, Any]], khoa: str) -> Decimal:
    return sum((_so(cot.get(khoa)) or Decimal(0) for cot in diem), Decimal(0))


def _mo_ta_cot(cot: Mapping[str, Any]) -> str:
    return (
        f"{ngay_vn(cot.get('bucketStart'))}: nộp {_dem(cot.get('applicationsSubmitted'))}, "
        f"duyệt {_dem(cot.get('applicationsApproved'))}, từ chối {_dem(cot.get('applicationsRejected'))}, "
        f"giải ngân {_dem(cot.get('loansDisbursed'))} khoản ({tien_vnd(cot.get('disbursedAmount'))})"
    )


async def _kpi_theo_thoi_gian(client: FinoraApiClient, params: KpiTheoThoiGianParams) -> ToolResult:
    """Chuỗi theo cột từ ``GET /api/v1/admin/loan-statistics/series``.

    Gọi ở chế độ chậm (30 giây): phần dư nợ theo ngày của API đọc lịch sử giao dịch từ Fineract.
    Tổng của cả khoảng được cộng sẵn ở đây vì guard kiểm số liệu (Task 13) chỉ chấp nhận con số có
    trong dữ kiện; để LLM tự cộng thì câu trả lời đúng cũng bị coi là bịa số.
    """
    tu, den = params.khoang()
    data = _doi_tuong(
        await client.get(
            ServiceName.LOAN,
            "/api/v1/admin/loan-statistics/series",
            {"from": tu.isoformat(), "to": den.isoformat(), "bucket": params.bucket},
            slow=True,
        )
    )
    diem = _danh_sach(data, "points")
    cot = data.get("bucket") if data.get("bucket") in _DO_RONG_COT else params.bucket
    pheu = _bang(data, "funnel")
    tong = {
        "ho_so_nop": so_nguyen(sum(_so_nguyen_json(c.get("applicationsSubmitted")) for c in diem)),
        "so_tien_de_nghi": tien_vnd(_tong_tien(diem, "applicationsSubmittedAmount")),
        "ho_so_duyet": so_nguyen(sum(_so_nguyen_json(c.get("applicationsApproved")) for c in diem)),
        "ho_so_tu_choi": so_nguyen(sum(_so_nguyen_json(c.get("applicationsRejected")) for c in diem)),
        "khoan_giai_ngan": so_nguyen(sum(_so_nguyen_json(c.get("loansDisbursed")) for c in diem)),
        "so_tien_giai_ngan": tien_vnd(_tong_tien(diem, "disbursedAmount")),
    }
    khoang = f"{ngay_vn(data.get('from') or tu)} đến {ngay_vn(data.get('to') or den)}"
    facts: dict[str, Any] = {
        "khoang": khoang,
        "do_rong_cot": _DO_RONG_COT[cot],
        "tong_trong_khoang": tong,
        "pheu_ho_so_nop_trong_khoang": {
            "nop": _dem(pheu.get("submitted")),
            "cham_diem_xong": _dem(pheu.get("scored")),
            "duoc_duyet": _dem(pheu.get("approved")),
            "xac_nhan_dieu_khoan": _dem(pheu.get("termsAccepted")),
            "yeu_cau_goi_von": _dem(pheu.get("fundingRequested")),
            "du_von": _dem(pheu.get("fullyFunded")),
            "giai_ngan": _dem(pheu.get("disbursed")),
        },
        "theo_cot_gan_nhat": [_mo_ta_cot(c) for c in diem[-SO_COT_GUI_LLM:]],
    }
    vi_the = [c.get("portfolio") for c in diem if isinstance(c.get("portfolio"), Mapping)]
    if vi_the:
        dau, cuoi = vi_the[0], vi_the[-1]
        facts["no_xau"] = {
            "ngay_dau": ngay_vn(dau.get("asOf")),
            "ty_le_dau": _ty_le(dau.get("nplRatioPercent"), "chưa tính được (dư nợ gốc bằng 0)"),
            "ngay_cuoi": ngay_vn(cuoi.get("asOf")),
            "ty_le_cuoi": _ty_le(cuoi.get("nplRatioPercent"), "chưa tính được (dư nợ gốc bằng 0)"),
        }
    khong_doc_duoc = _so_nguyen_json(_lay(data, "portfolioHistory", "unavailableLoans"))
    if khong_doc_duoc > 0:
        facts["canh_bao"] = (
            f"{so_nguyen(khong_doc_duoc)} khoản vay không đọc được lịch sử từ hệ thống lõi, "
            "nên dư nợ theo ngày có thể thấp hơn thực tế."
        )

    muc_the = [
        CardItem(label="Hồ sơ nộp", value=tong["ho_so_nop"]),
        CardItem(label="Số tiền đề nghị", value=tong["so_tien_de_nghi"]),
        CardItem(label="Được duyệt", value=tong["ho_so_duyet"]),
        CardItem(label="Bị từ chối", value=tong["ho_so_tu_choi"]),
        CardItem(label="Khoản giải ngân", value=tong["khoan_giai_ngan"]),
        CardItem(label="Tiền giải ngân", value=tong["so_tien_giai_ngan"]),
    ]
    if "no_xau" in facts:
        muc_the.append(
            CardItem(
                label="Tỷ lệ nợ xấu đầu → cuối",
                value=f"{facts['no_xau']['ty_le_dau']} → {facts['no_xau']['ty_le_cuoi']}",
            )
        )
    the_cot = Card(
        kind="table",
        title=f"Theo {_DO_RONG_COT[cot]}",
        columns=["Từ ngày", "Nộp", "Duyệt", "Từ chối", "Giải ngân"],
        rows=[
            [
                ngay_vn(c.get("bucketStart")),
                _dem(c.get("applicationsSubmitted")),
                _dem(c.get("applicationsApproved")),
                _dem(c.get("applicationsRejected")),
                tien_vnd(c.get("disbursedAmount")),
            ]
            for c in diem[-SO_COT_GUI_LLM:]
        ],
    )
    return ToolResult(
        facts=facts,
        cards=[Card(kind="kv", title=f"Từ {khoang}", items=muc_the), the_cot],
        actions=[build_action("trang_tong_quan")],
    )


# ── kpi_nguoi_dung ────────────────────────────────────────────────────────────


async def _kpi_nguoi_dung(client: FinoraApiClient, params: NoParams) -> ToolResult:
    """Số người dùng theo vai trò và eKYC từ ``GET /api/v1/admin/users/stats`` (UserStatsResponse)."""
    data = _doi_tuong(await client.get(ServiceName.USER, "/api/v1/admin/users/stats"))
    theo_ekyc = _bang(data, "byEkycStatus")
    facts = {
        "tong": _dem(data.get("total")),
        "theo_vai_tro": _dem_theo(data.get("byRole"), lambda ma: _nhan(_VAI_TRO, ma)),
        "theo_ekyc": _dem_theo(theo_ekyc, lambda ma: dich_trang_thai("ekyc", ma)),
        "cho_xac_minh_tay": _dem_trong(theo_ekyc, "MANUAL_REVIEW"),
    }
    muc = [CardItem(label="Tổng người dùng", value=facts["tong"])]
    muc += [CardItem(label=nhan, value=so) for nhan, so in facts["theo_vai_tro"].items()]
    muc += [CardItem(label=f"eKYC: {nhan}", value=so) for nhan, so in facts["theo_ekyc"].items()]
    return ToolResult(
        facts=facts,
        cards=[Card(kind="kv", title="Người dùng", items=muc)],
        actions=[build_action("khach_hang_kyc")],
    )


# ── kpi_dau_tu ────────────────────────────────────────────────────────────────


async def _kpi_dau_tu(client: FinoraApiClient, params: NoParams) -> ToolResult:
    """Sàn gọi vốn và Notes từ ``GET /api/v1/investments/admin/statistics/summary`` (tiền là JSON number)."""
    data = _doi_tuong(
        await client.get(ServiceName.INVESTMENT, "/api/v1/investments/admin/statistics/summary")
    )
    theo_khoan = _bang(data, "listings", "byStatus")
    goi_von = _bang(data, "openFunding")
    theo_note = _bang(data, "notes", "byStatus")
    facts = {
        "thoi_diem": ngay_gio_vn(data.get("asOf")),
        "khoan_tren_san": _dem_theo(theo_khoan, lambda ma: dich_trang_thai("listing", ma)),
        "cho_duyet_len_san": _dem_trong(theo_khoan, "DRAFT"),
        "dang_goi_von": {
            "so_khoan": _dem(goi_von.get("listings")),
            "muc_tieu": tien_vnd(goi_von.get("targetAmount")),
            "da_cam_ket": tien_vnd(goi_von.get("committedAmount")),
            "ty_le_lap_day": _ty_le(goi_von.get("fillRatePercent"), "chưa tính được (không có khoản đang mở)"),
        },
        "notes": _dem_theo(theo_note, lambda ma: dich_trang_thai("note", ma)),
        "note_vo_no": _dem_trong(theo_note, "DEFAULTED"),
        "du_no_goc_note_dang_hoat_dong": tien_vnd(_lay(data, "notes", "activeOutstandingPrincipal")),
        "auto_invest_dang_bat": _dem(_lay(data, "autoInvest", "activeConfigs")),
    }
    dang_goi = facts["dang_goi_von"]
    the = Card(
        kind="kv",
        title="Sàn gọi vốn và Notes",
        items=[
            CardItem(label="Khoản đang gọi vốn", value=dang_goi["so_khoan"]),
            CardItem(label="Đã cam kết / mục tiêu", value=f"{dang_goi['da_cam_ket']} / {dang_goi['muc_tieu']}"),
            CardItem(label="Tỷ lệ lấp đầy", value=dang_goi["ty_le_lap_day"]),
            CardItem(label="Khoản chờ duyệt lên sàn", value=facts["cho_duyet_len_san"]),
            CardItem(label="Note vỡ nợ", value=facts["note_vo_no"]),
            CardItem(label="Auto-Invest đang bật", value=facts["auto_invest_dang_bat"]),
        ],
    )
    return ToolResult(
        facts=facts,
        cards=[the],
        actions=[build_action("goi_von"), build_action("cho_notes")],
    )


# ── viec_can_lam ──────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class HangDoi:
    """Một hàng đợi vận hành: đếm bằng ``totalElements`` của trang ``size=1`` (đọc 1 dòng, lấy tổng)."""

    ma: str
    ten: str
    service: ServiceName
    path: str
    query: Mapping[str, str]
    action_key: str | None
    action_params: Mapping[str, str]
    action_label: str | None


# Thứ tự = thứ tự hiển thị và thứ tự ưu tiên nút. Giá trị trạng thái lấy đúng enum Java:
# LoanApplicationStatus, CollectionCaseStatus, LoanRescheduleStatus, ReconciliationIncidentStatus,
# RepaymentEventQuarantineStatus, SettlementStatus (tham số của Investment tên là "settlement").
HANG_DOI_VIEC_CAN_LAM: tuple[HangDoi, ...] = (
    HangDoi("ho_so_cho_tham_dinh", "Hồ sơ chờ thẩm định", ServiceName.LOAN,
            "/api/v1/admin/loan-applications", {"status": "PENDING_REVIEW"},
            "hang_doi_tham_dinh", {}, "Mở hàng đợi thẩm định"),
    HangDoi("ho_so_cho_cham_lai", "Hồ sơ chờ chấm lại điểm", ServiceName.LOAN,
            "/api/v1/admin/loan-applications", {"status": "SCORING_RETRY_PENDING"},
            None, {}, None),
    HangDoi("thu_hoi_dang_mo", "Hồ sơ thu hồi đang mở", ServiceName.LOAN,
            "/api/v1/admin/collection-cases", {"status": "OPEN"},
            "van_hanh", {"tab": "collection"}, "Mở thu hồi"),
    HangDoi("co_cau_cho_duyet", "Yêu cầu cơ cấu chờ duyệt", ServiceName.LOAN,
            "/api/v1/admin/loan-reschedule-requests", {"status": "PENDING_REVIEW"},
            "van_hanh", {"tab": "reschedule"}, "Mở yêu cầu cơ cấu"),
    HangDoi("su_co_doi_soat", "Sai lệch đối soát chưa xử lý", ServiceName.LOAN,
            "/api/v1/admin/loan-servicing-reconciliation/incidents", {"status": "OPEN"},
            "van_hanh", {"tab": "reconciliation"}, "Mở đối soát"),
    HangDoi("su_kien_tra_no_cho_ghep", "Sự kiện trả nợ chờ ghép", ServiceName.LOAN,
            "/api/v1/admin/repayment-event-quarantine", {"status": "PENDING"},
            "van_hanh", {"tab": "quarantine"}, "Mở sự kiện chờ ghép"),
    HangDoi("notes_thanh_toan_loi", "Giao dịch Notes thanh toán lỗi", ServiceName.INVESTMENT,
            "/api/v1/investments/admin/order-books/trades", {"settlement": "FAILED"},
            "cho_notes", {}, "Mở chợ Notes"),
)


async def _dem_hang_doi(client: FinoraApiClient, hang_doi: HangDoi) -> int | ToolError:
    """Đếm một hàng đợi; lỗi trả về như giá trị để một hàng hỏng không kéo cả câu trả lời hỏng theo."""
    try:
        trang = await client.get(hang_doi.service, hang_doi.path, {**hang_doi.query, "page": 0, "size": 1})
    except ToolError as loi:
        return loi
    # Loan bọc trang bằng {data, page, size, totalElements}; Investment bằng PageResponse của common
    # {content, ..., totalElements}. Cả hai đều có totalElements nên chỉ đọc khóa đó.
    tong = trang.get("totalElements") if isinstance(trang, Mapping) else None
    if not isinstance(tong, int) or isinstance(tong, bool) or tong < 0:
        return ToolError("DU_LIEU_KHONG_HOP_LE", "Dữ liệu hàng đợi không đúng định dạng.")
    return tong


async def _viec_can_lam(client: FinoraApiClient, params: NoParams) -> ToolResult:
    """Đếm 7 hàng đợi cùng lúc (7 GET song song, mỗi GET ``size=1``), không gọi theo từng phần tử.

    Một hàng lỗi → hàng đó hiện "không lấy được", các hàng khác vẫn trả. Riêng 401 thì ném lại để
    client làm mới token và gửi lại cả lượt; mọi hàng đều lỗi thì báo lỗi dịch vụ như các tool khác.
    """
    ket_qua = await asyncio.gather(
        *(_dem_hang_doi(client, hang_doi) for hang_doi in HANG_DOI_VIEC_CAN_LAM),
        return_exceptions=True,
    )
    for kq in ket_qua:
        if isinstance(kq, ToolError) and kq.code == "PHIEN_HET_HAN":
            raise kq
        if isinstance(kq, BaseException) and not isinstance(kq, ToolError):
            raise kq
    if all(isinstance(kq, ToolError) for kq in ket_qua):
        raise ToolError("DICH_VU_LOI", "Chưa lấy được danh sách việc cần làm, thử lại sau.")

    viec: list[dict[str, str]] = []
    khong_lay_duoc: list[str] = []
    nut = []
    for hang_doi, kq in zip(HANG_DOI_VIEC_CAN_LAM, ket_qua, strict=True):
        if isinstance(kq, int):
            viec.append({"viec": hang_doi.ten, "so_luong": so_nguyen(kq)})
            if kq > 0 and hang_doi.action_key and len(nut) < SO_NUT_TOI_DA:
                nut.append(build_action(hang_doi.action_key, hang_doi.action_params, hang_doi.action_label))
        else:
            viec.append({"viec": hang_doi.ten, "so_luong": KHONG_LAY_DUOC})
            khong_lay_duoc.append(hang_doi.ten)

    facts = {
        "viec": viec,
        "khong_lay_duoc": khong_lay_duoc,
        "ghi_chu": "Số lượng là tổng số mục đang chờ trong từng hàng đợi tại thời điểm hỏi.",
    }
    the = Card(
        kind="kv",
        title="Việc cần xử lý",
        items=[CardItem(label=v["viec"], value=v["so_luong"]) for v in viec],
    )
    return ToolResult(facts=facts, cards=[the], actions=nut)


# ── danh_sach_ho_so ───────────────────────────────────────────────────────────


def _dong_ho_so(item: Mapping[str, Any]) -> dict[str, Any] | None:
    """Một dòng AdminLoanReviewSummaryResponse → dữ kiện. Không đọc borrowerId, adminDecidedBy."""
    ma = item.get("applicationNumber")
    if not isinstance(ma, str) or not _MA_HO_SO.fullmatch(ma):
        return None
    danh_gia = _bang(item, "assessment")
    hang = danh_gia.get("creditGrade")
    de_xuat = danh_gia.get("aiRecommendation")
    dong: dict[str, Any] = {
        "ma_ho_so": ma,
        "trang_thai": dich_trang_thai("application", item.get("status")),
        "so_tien_de_nghi": tien_vnd(item.get("requestedAmount")),
        "ky_han": _thang(item.get("requestedTermMonths")),
        "hang": hang if isinstance(hang, str) and _TEN_HANG.fullmatch(hang) else "—",
        "diem_tong_hop": _diem(danh_gia.get("evaluationScore")),
        "ai_de_xuat": dich_trang_thai("application", de_xuat) if isinstance(de_xuat, str) else "—",
        "ngay_nop": ngay_vn(item.get("submittedAt")),
    }
    ly_do = _ma_ly_do(danh_gia.get("rejectionReason"))
    if ly_do:
        dong["ly_do_tu_choi"] = [dich_ly_do(m) for m in ly_do]
    return dong


async def _danh_sach_ho_so(client: FinoraApiClient, params: DanhSachHoSoParams) -> ToolResult:
    """20 hồ sơ mới nhất (Loan sắp createdAt giảm dần) của một trạng thái, kèm tổng số."""
    trang = _doi_tuong(
        await client.get(
            ServiceName.LOAN,
            "/api/v1/admin/loan-applications",
            {"status": params.status, "page": 0, "size": SO_HO_SO_MOI_TRANG},
        )
    )
    ho_so = [d for d in (_dong_ho_so(item) for item in _danh_sach(trang, "data")) if d is not None]
    trang_thai = dich_trang_thai("application", params.status)
    facts = {
        "trang_thai_loc": trang_thai,
        "tong": _dem(trang.get("totalElements")),
        "dang_hien": so_nguyen(len(ho_so)),
        "ho_so": ho_so,
    }
    the = Card(
        kind="table",
        title=f"Hồ sơ {trang_thai} ({facts['tong']})",
        columns=["Mã hồ sơ", "Số tiền", "Kỳ hạn", "Hạng", "Điểm", "Ngày nộp"],
        rows=[
            [d["ma_ho_so"], d["so_tien_de_nghi"], d["ky_han"], d["hang"], d["diem_tong_hop"], d["ngay_nop"]]
            for d in ho_so
        ],
    )
    nut = []
    if params.status == "PENDING_REVIEW":
        nut.append(build_action("hang_doi_tham_dinh", label="Mở hàng đợi thẩm định"))
    nut += [
        build_action("mo_tham_dinh", {"applicationNumber": d["ma_ho_so"]}, f"Mở hồ sơ {d['ma_ho_so']}")
        for d in ho_so[:2]
    ]
    return ToolResult(
        facts=facts,
        cards=[the],
        actions=nut,
        ids=frozenset(d["ma_ho_so"] for d in ho_so),
    )


# ── tom_tat_ho_so ─────────────────────────────────────────────────────────────


def _luat_cho_llm(vet_luat: list[Mapping[str, Any]]) -> list[dict[str, str]]:
    """Vết chấm luật → dữ kiện, luật ít điểm nhất đứng đầu, luật thiếu dữ liệu xếp cuối.

    Chỉ chép mã, mô tả (admin viết trong cấu hình, không phải lời người vay), điểm/tối đa và cờ thiếu
    dữ liệu. Bỏ ``gia_tri`` vì đó là số liệu thật của người vay (DTI chính xác, điểm CIC, tình trạng nhà
    ở) và bỏ ``trong_so``/``goi_y`` vì không giúp giải thích hồ sơ này. Luật thiếu dữ liệu nhận điểm
    trung tính nên không nói gì về chất lượng hồ sơ, vì vậy không được đứng đầu danh sách "yếu nhất".
    """
    dong: list[tuple[bool, Decimal, dict[str, str]]] = []
    for vet in vet_luat:
        ma = vet.get("ma")
        if not isinstance(ma, str) or not _MA_KY_THUAT.fullmatch(ma):
            continue
        diem = _so_nguyen_json(vet.get("diem"))
        toi_da = _so_nguyen_json(vet.get("toi_da"))
        thieu = vet.get("thieu_du_lieu") is True
        mo_ta = vet.get("mo_ta")
        ty_le = Decimal(diem) / Decimal(toi_da) if toi_da > 0 else Decimal(0)
        dong.append(
            (
                thieu,
                ty_le,
                {
                    "ma": ma,
                    "mo_ta": mo_ta[:120] if isinstance(mo_ta, str) else "",
                    "diem": f"{diem}/{toi_da}",
                    "thieu_du_lieu": "có" if thieu else "không",
                },
            )
        )
    dong.sort(key=lambda d: (d[0], d[1]))
    return [d for _, _, d in dong]


def _ly_do_lich_su(review: Mapping[str, Any]) -> list[dict[str, str]]:
    """Mã lý do trong lịch sử trạng thái (ví dụ AI_POLICY_REQUIRES_REVIEW), bỏ trùng, giữ thứ tự.

    Không đọc ``reasonDetail`` (ghi chú tự do của admin, có thể chứa tên/SĐT) và ``actorId``.
    """
    da_co: set[str] = set()
    ket_qua: list[dict[str, str]] = []
    for dong in _danh_sach(review, "recentHistory"):
        ma = dong.get("reasonCode")
        if isinstance(ma, str) and _MA_KY_THUAT.fullmatch(ma) and ma not in da_co:
            da_co.add(ma)
            ket_qua.append({"ma": ma, "y_nghia": dich_ly_do(ma)})
    return ket_qua


async def _tom_tat_ho_so(client: FinoraApiClient, params: TomTatHoSoParams) -> ToolResult:
    """Tóm tắt một hồ sơ từ ``/review`` + ``/explanation`` của Loan (hai GET song song).

    ``/explanation`` trả 404 khi hồ sơ chưa từng chấm và 400 ``ASSESSMENT_EXPLANATION_UNAVAILABLE`` khi
    lần chấm lỗi; khi đó vẫn tóm tắt phần còn lại thay vì báo lỗi cả câu.
    """
    ma = params.application_number
    goc = f"/api/v1/admin/loan-applications/{ma}"
    review, giai_thich = await asyncio.gather(
        client.get(ServiceName.LOAN, f"{goc}/review"),
        client.get(ServiceName.LOAN, f"{goc}/explanation"),
        return_exceptions=True,
    )
    if isinstance(review, BaseException):
        raise review
    if isinstance(giai_thich, ToolError):
        if giai_thich.code == "PHIEN_HET_HAN":
            raise giai_thich
        giai_thich = None
    elif isinstance(giai_thich, BaseException):
        raise giai_thich
    review = _doi_tuong(review)

    danh_gia = _bang(review, "assessment")
    tai_chinh = _bang(review, "financialInformation")
    hang = danh_gia.get("creditGrade")
    de_xuat = danh_gia.get("aiRecommendation")
    phien_ban = danh_gia.get("decisionPolicyVersion")
    phien_ban = phien_ban if isinstance(phien_ban, str) and _MA_KY_THUAT.fullmatch(phien_ban) else None
    ma_tu_choi = _ma_ly_do(danh_gia.get("rejectionReason"))
    nguon = review.get("decisionSource")
    luat = _luat_cho_llm(_danh_sach(giai_thich, "ruleTrace"))

    facts: dict[str, Any] = {
        "ma_ho_so": ma,
        "trang_thai": dich_trang_thai("application", review.get("status")),
        # Số tiền và kỳ hạn là điều khoản khoản vay, không định danh được người vay; thiếu chúng thì
        # bản tóm tắt không nói được hồ sơ xin gì.
        "khoan_de_nghi": {
            "so_tien": tien_vnd(review.get("requestedAmount")),
            "ky_han": _thang(review.get("requestedTermMonths")),
        },
        "nguon_quyet_dinh": _nhan(_NGUON_QUYET_DINH, nguon) if nguon else "Chưa có quyết định cuối",
        "cham_diem": {
            "trang_thai": _nhan(_TRANG_THAI_CHAM, danh_gia.get("status")) if danh_gia else "Chưa chấm điểm",
            "diem_tong_hop": _diem(danh_gia.get("evaluationScore")),
            "diem_luat": _diem(danh_gia.get("riskScore")),
            "xac_suat_vo_no": _pd(danh_gia.get("pdProbability")),
            "hang": hang if isinstance(hang, str) and _TEN_HANG.fullmatch(hang) else "—",
            "ai_de_xuat": dich_trang_thai("application", de_xuat) if isinstance(de_xuat, str) else "—",
            "ma_ly_do_tu_choi": ma_tu_choi,
            "ly_do_tu_choi": [dich_ly_do(m) for m in ma_tu_choi],
            "phien_ban_chinh_sach": phien_ban or "—",
        },
        "tai_chinh_khoang": {
            "thu_nhap_thang": khoang_thu_nhap(tai_chinh.get("declaredMonthlyIncome")),
            "nghia_vu_no_thang": khoang_no_thang(tai_chinh.get("monthlyDebtObligations")),
            "dti": khoang_dti(tai_chinh.get("dtiSnapshot")),
        },
        "luat": luat,
        "ly_do_trong_lich_su": _ly_do_lich_su(review),
    }
    ma_dieu_kien = _lay(review, "eligibility", "reasonCode")
    if isinstance(ma_dieu_kien, str) and _MA_KY_THUAT.fullmatch(ma_dieu_kien):
        facts["ly_do_dieu_kien"] = {"ma": ma_dieu_kien, "y_nghia": dich_ly_do(ma_dieu_kien)}
    if giai_thich is None:
        facts["vet_luat"] = "Chưa có vết chấm luật (hồ sơ chưa chấm xong hoặc lần chấm bị lỗi)."

    cham = facts["cham_diem"]
    tc = facts["tai_chinh_khoang"]
    the_ho_so = Card(
        kind="kv",
        title=f"Hồ sơ {ma}",
        items=[
            CardItem(label="Trạng thái", value=facts["trang_thai"]),
            CardItem(label="Số tiền đề nghị", value=facts["khoan_de_nghi"]["so_tien"]),
            CardItem(label="Kỳ hạn", value=facts["khoan_de_nghi"]["ky_han"]),
            CardItem(label="Điểm tổng hợp", value=cham["diem_tong_hop"]),
            CardItem(label="Hạng", value=cham["hang"]),
            CardItem(label="Xác suất vỡ nợ", value=cham["xac_suat_vo_no"]),
            CardItem(label="AI đề xuất", value=cham["ai_de_xuat"]),
            CardItem(label="Thu nhập (khoảng)", value=tc["thu_nhap_thang"]),
            CardItem(label="Nợ hằng tháng (khoảng)", value=tc["nghia_vu_no_thang"]),
            CardItem(label="DTI (khoảng)", value=tc["dti"]),
        ],
    )
    the: list[Card] = [the_ho_so]
    if luat:
        the.append(
            Card(
                kind="table",
                title="Điểm theo luật",
                columns=["Luật", "Điểm", "Thiếu dữ liệu"],
                rows=[[muc["mo_ta"] or muc["ma"], muc["diem"], muc["thieu_du_lieu"]] for muc in luat],
            )
        )
    return ToolResult(
        facts=facts,
        cards=the,
        actions=[
            build_action("mo_tham_dinh", {"applicationNumber": ma}, "Mở trang thẩm định"),
            build_action("chinh_sach_ai", label="Xem chính sách chấm điểm"),
        ],
        citations=[_trich_dan_chinh_sach(phien_ban)] if phien_ban else [],
        ids=frozenset({ma}),
    )


# ── chinh_sach_ai ─────────────────────────────────────────────────────────────


def _bang_hang() -> list[dict[str, str]]:
    """Bảng hạng đúng cách ``rule_engine.xep_hang`` đọc: sắp min_score giảm dần, điểm >= min_score là đạt.

    Vì vậy hạng dưới chạy "từ min đến dưới min của hạng trên"; chỉ hạng cao nhất gồm cả max_score.
    """
    hang = sorted(product_config.get_grades(), key=lambda g: g["min_score"], reverse=True)
    ket_qua = []
    for vi_tri, g in enumerate(hang):
        if vi_tri == 0:
            khoang = f"từ {g['min_score']} đến {g['max_score']}"
        else:
            khoang = f"từ {g['min_score']} đến dưới {hang[vi_tri - 1]['min_score']}"
        han_muc = g.get("limit") or 0
        ket_qua.append(
            {
                "hang": str(g["grade"]),
                "khoang_diem": khoang,
                "han_muc": tien_vnd(han_muc) if han_muc > 0 else "không cấp hạn mức",
            }
        )
    return ket_qua


async def _chinh_sach_ai(client: FinoraApiClient, params: NoParams) -> ToolResult:
    """Đọc cấu hình đang chạy trong tiến trình (``app/services/credit/product_config``), không gọi HTTP.

    finora-ai chính là nơi giữ ``config/product_config.json``; đọc qua module này (không đọc file
    riêng) để câu trả lời luôn khớp giá trị ``rule_engine.quyet_dinh`` đang dùng, kể cả ngay sau khi
    admin sửa cấu hình. Thứ tự quyết định chép đúng docstring của ``quyet_dinh``.
    """
    phien_ban = product_config.get_decision_policy_version()
    nguong = product_config.get_approval_thresholds()
    trong_so = product_config.get_model_weights()
    bang_hang = _bang_hang()
    luat = product_config.get_rules()
    luat_bat = [muc for muc in luat if muc.get("bat", True)]
    tu_duyet = _diem(nguong["auto_approve"])
    tu_choi = _diem(nguong["auto_reject"])
    ten_hang = f"{bang_hang[0]['hang']}–{bang_hang[-1]['hang']}" if bang_hang else "—"

    facts = {
        "phien_ban": phien_ban,
        "loai_noi_dung": "Chính sách nội bộ FINORA, không phải quy định pháp luật.",
        "nguong_quyet_dinh": {
            "tu_duyet_tu_diem": tu_duyet,
            "tu_choi_duoi_diem": tu_choi,
            "vung_cho_tham_dinh": f"từ {tu_choi} đến dưới {tu_duyet} điểm",
        },
        "thu_tu_quyet_dinh": [
            "Vi phạm chốt chặn cứng thì từ chối, bất kể điểm.",
            "Có lý do bắt buộc thẩm định hoặc thiếu dữ liệu để chấm tin cậy thì chờ thẩm định, bất kể điểm.",
            "Còn lại so điểm tổng hợp với hai ngưỡng trên.",
        ],
        "cong_thuc_diem": (
            f"Điểm tổng hợp = (1 − PD) × 100 × {_diem(trong_so['pd_weight'])} "
            f"+ điểm luật × {_diem(trong_so['risk_weight'])}"
        ),
        "trong_so": {
            "mo_hinh_pd": phan_tram(Decimal(str(trong_so["pd_weight"])) * 100),
            "bo_luat": phan_tram(Decimal(str(trong_so["risk_weight"])) * 100),
        },
        "hang_tin_dung": bang_hang,
        "vai_tro_cua_hang": (
            f"Hạng {ten_hang} dùng để định giá lãi suất và hạn mức; quyết định duyệt dùng ngưỡng điểm riêng."
        ),
        "luat_dang_bat": [
            {
                "ma": str(muc.get("ma")),
                "mo_ta": str(muc.get("mo_ta") or "")[:120],
                "trong_so": _diem(muc.get("trong_so")),
            }
            for muc in luat_bat
        ],
        "so_luat_dang_tat": so_nguyen(len(luat) - len(luat_bat)),
    }
    the_nguong = Card(
        kind="kv",
        title=f"Ngưỡng quyết định ({phien_ban})",
        items=[
            CardItem(label="Tự duyệt", value=f"từ {tu_duyet} điểm"),
            CardItem(label="Tự từ chối", value=f"dưới {tu_choi} điểm"),
            CardItem(label="Chờ thẩm định", value=facts["nguong_quyet_dinh"]["vung_cho_tham_dinh"]),
            CardItem(
                label="Trọng số",
                value=f"PD {facts['trong_so']['mo_hinh_pd']} · luật {facts['trong_so']['bo_luat']}",
            ),
        ],
    )
    the_hang = Card(
        kind="table",
        title="Bảng hạng",
        columns=["Hạng", "Khoảng điểm", "Hạn mức"],
        rows=[[h["hang"], h["khoang_diem"], h["han_muc"]] for h in bang_hang],
    )
    return ToolResult(
        facts=facts,
        cards=[the_nguong, the_hang],
        actions=[build_action("chinh_sach_ai", label="Mở chính sách chấm điểm")],
        citations=[_trich_dan_chinh_sach(phien_ban)],
    )


# ── Câu mẫu cho nút gợi ý (đường A, không gọi LLM) ────────────────────────────


def _render_kpi(kq: ToolResult) -> str:
    f = kq.facts
    dm = f["danh_muc"]
    cau = (
        f"Tính đến {f['thoi_diem']}, có {dm['khoan_con_du_no']} khoản vay còn dư nợ với dư nợ gốc "
        f"{dm['du_no_goc']}, số tiền quá hạn {dm['so_tien_qua_han']}. Tỷ lệ nợ xấu là "
        f"{dm['ty_le_no_xau']}. Đang có {f['ho_so']['cho_tham_dinh']} hồ sơ chờ thẩm định và "
        f"{f['thu_hoi']['dang_mo']} hồ sơ thu hồi đang mở."
    )
    if "canh_bao" in f:
        cau += f" Lưu ý: {f['canh_bao']}"
    return cau


def _render_viec(kq: ToolResult) -> str:
    f = kq.facts
    dong = [
        f"- {v['viec']}: {v['so_luong']}" for v in f["viec"] if v["so_luong"] not in ("0", KHONG_LAY_DUOC)
    ]
    if dong:
        cau = "Việc cần xử lý hiện tại:\n" + "\n".join(dong)
    else:
        cau = "Hiện không có việc nào đang chờ trong các hàng đợi."
    if f["khong_lay_duoc"]:
        cau += "\nChưa lấy được số liệu: " + ", ".join(f["khong_lay_duoc"]) + "."
    return cau


def _render_tom_tat(kq: ToolResult) -> str:
    f = kq.facts
    cd = f["cham_diem"]
    tc = f["tai_chinh_khoang"]
    cau = [
        f"Hồ sơ {f['ma_ho_so']} ({f['trang_thai']}) đề nghị vay {f['khoan_de_nghi']['so_tien']} "
        f"trong {f['khoan_de_nghi']['ky_han']}."
    ]
    if cd["diem_tong_hop"] != "—":
        cau.append(
            f"Điểm tổng hợp {cd['diem_tong_hop']}, hạng {cd['hang']}, xác suất vỡ nợ {cd['xac_suat_vo_no']}; "
            f"AI đề xuất: {cd['ai_de_xuat']}."
        )
    else:
        cau.append(f"Chưa có kết quả chấm điểm ({cd['trang_thai']}).")
    cau.append(f"Thu nhập khai báo {tc['thu_nhap_thang']}, nợ hằng tháng {tc['nghia_vu_no_thang']}, DTI {tc['dti']}.")
    yeu = [muc for muc in f["luat"] if muc["thieu_du_lieu"] == "không"][:2]
    if yeu:
        mo_ta_yeu = "; ".join(f"{muc['mo_ta'] or muc['ma']} ({muc['diem']})" for muc in yeu)
        cau.append(f"Luật được ít điểm nhất: {mo_ta_yeu}.")
    thieu = [muc["mo_ta"] or muc["ma"] for muc in f["luat"] if muc["thieu_du_lieu"] == "có"]
    if thieu:
        cau.append("Thiếu dữ liệu: " + _noi_y(thieu))
    if cd["ly_do_tu_choi"]:
        cau.append("Lý do từ chối: " + _noi_y(cd["ly_do_tu_choi"]))
    if f["ly_do_trong_lich_su"]:
        cau.append("Lịch sử ghi nhận: " + _noi_y(muc["y_nghia"] for muc in f["ly_do_trong_lich_su"]))
    return " ".join(cau)


def _noi_y(cac_y: Iterable[str]) -> str:
    """Nối các ý bằng "; " rồi chấm câu một lần: câu dịch của glossary đã có dấu chấm cuối (fix-2: ".;", "..")."""
    return "; ".join(y.strip().rstrip(".").strip() for y in cac_y if y and y.strip()) + "."


def _render_chinh_sach(kq: ToolResult) -> str:
    f = kq.facts
    n = f["nguong_quyet_dinh"]
    return (
        f"Theo {f['phien_ban']} (chính sách nội bộ FINORA, không phải quy định pháp luật): hồ sơ từ "
        f"{n['tu_duyet_tu_diem']} điểm được tự duyệt, dưới {n['tu_choi_duoi_diem']} điểm bị tự từ chối, "
        f"{n['vung_cho_tham_dinh']} chuyển thẩm định viên. {f['vai_tro_cua_hang']} Hồ sơ vi phạm chốt chặn "
        "cứng bị từ chối bất kể điểm; hồ sơ thiếu dữ liệu hoặc có lý do bắt buộc thẩm định thì chờ duyệt "
        "tay dù điểm cao."
    )


def _render_danh_sach(kq: ToolResult) -> str:
    f = kq.facts
    if not f["ho_so"]:
        return f"Hiện không có hồ sơ nào ở trạng thái {f['trang_thai_loc']}."
    vi_du = ", ".join(h["ma_ho_so"] for h in f["ho_so"][:3])
    return (
        f"Có {f['tong']} hồ sơ ở trạng thái {f['trang_thai_loc']}. Đang hiện {f['dang_hien']} hồ sơ "
        f"mới nhất, gần nhất là {vi_du}."
    )


# ── Khai báo cho registry ─────────────────────────────────────────────────────


def _get(service: ServiceName, pattern: str) -> RouteRule:
    return RouteRule(method="GET", service=service, pattern=pattern)


# dict.fromkeys bỏ trùng mà giữ thứ tự: hai hàng đợi hồ sơ dùng chung một path.
_ROUTES_VIEC_CAN_LAM = tuple(
    dict.fromkeys(_get(h.service, re.escape(h.path)) for h in HANG_DOI_VIEC_CAN_LAM)
)

ADMIN_TOOLS: tuple[ToolSpec, ...] = (
    ToolSpec(
        name="kpi_khoan_vay",
        toolset=Toolset.ADMIN,
        description=(
            "Số liệu hiện tại của danh mục cho vay: hồ sơ theo trạng thái, khoản còn dư nợ, dư nợ gốc, "
            "quá hạn, nợ xấu (nhóm 3–5), thu hồi, cơ cấu, phân bố hạng. Dùng khi hỏi tình hình chung, "
            "tỷ lệ nợ xấu, dư nợ."
        ),
        params_model=NoParams,
        handler=_kpi_khoan_vay,
        lane=Lane.PUBLIC,
        routes=(_get(ServiceName.LOAN, r"/api/v1/admin/loan-statistics/summary"),),
    ),
    ToolSpec(
        name="kpi_theo_thoi_gian",
        toolset=Toolset.ADMIN,
        description=(
            "Diễn biến trong một khoảng ngày: hồ sơ nộp/duyệt/từ chối, giải ngân, phễu hồ sơ, tỷ lệ nợ "
            "xấu đầu và cuối kỳ. fromDate/toDate dạng YYYY-MM-DD, tối đa 366 ngày; bucket DAY, WEEK "
            "hoặc MONTH. Bỏ trống ngày thì lấy 30 ngày gần nhất."
        ),
        params_model=KpiTheoThoiGianParams,
        handler=_kpi_theo_thoi_gian,
        lane=Lane.PUBLIC,
        routes=(_get(ServiceName.LOAN, r"/api/v1/admin/loan-statistics/series"),),
        slow=True,
    ),
    ToolSpec(
        name="kpi_nguoi_dung",
        toolset=Toolset.ADMIN,
        description="Số người dùng theo vai trò (người vay, nhà đầu tư, quản trị) và theo trạng thái eKYC.",
        params_model=NoParams,
        handler=_kpi_nguoi_dung,
        lane=Lane.PUBLIC,
        routes=(_get(ServiceName.USER, r"/api/v1/admin/users/stats"),),
    ),
    ToolSpec(
        name="kpi_dau_tu",
        toolset=Toolset.ADMIN,
        description=(
            "Sàn gọi vốn và chợ Notes: khoản theo trạng thái, vốn đang gom và tỷ lệ lấp đầy, Note theo "
            "trạng thái, dư nợ gốc Note đang hoạt động, số cấu hình Auto-Invest đang bật."
        ),
        params_model=NoParams,
        handler=_kpi_dau_tu,
        lane=Lane.PUBLIC,
        routes=(_get(ServiceName.INVESTMENT, r"/api/v1/investments/admin/statistics/summary"),),
    ),
    ToolSpec(
        name="viec_can_lam",
        toolset=Toolset.ADMIN,
        description=(
            "Đếm việc đang chờ trong 7 hàng đợi vận hành: hồ sơ chờ thẩm định, hồ sơ chờ chấm lại, thu "
            "hồi đang mở, cơ cấu chờ duyệt, sai lệch đối soát, sự kiện trả nợ chờ ghép, giao dịch Notes "
            "thanh toán lỗi. Dùng khi hỏi hôm nay cần xử lý gì."
        ),
        params_model=NoParams,
        handler=_viec_can_lam,
        lane=Lane.PUBLIC,
        routes=_ROUTES_VIEC_CAN_LAM,
    ),
    ToolSpec(
        name="danh_sach_ho_so",
        toolset=Toolset.ADMIN,
        description=(
            "20 hồ sơ mới nhất của một trạng thái (mặc định PENDING_REVIEW) kèm tổng số: mã hồ sơ, số "
            "tiền, kỳ hạn, hạng, điểm, đề xuất của AI. Không có thông tin định danh người vay."
        ),
        params_model=DanhSachHoSoParams,
        handler=_danh_sach_ho_so,
        lane=Lane.USER_DATA,
        routes=(_get(ServiceName.LOAN, r"/api/v1/admin/loan-applications"),),
    ),
    ToolSpec(
        name="tom_tat_ho_so",
        toolset=Toolset.ADMIN,
        description=(
            "Tóm tắt một hồ sơ từ bản chấm điểm đã lưu: điểm, hạng, xác suất vỡ nợ, đề xuất của AI, mã "
            "lý do, điểm từng luật, thu nhập/nợ/DTI theo khoảng. Không chấm lại, không có định danh."
        ),
        params_model=TomTatHoSoParams,
        handler=_tom_tat_ho_so,
        lane=Lane.USER_DATA,
        routes=(
            _get(ServiceName.LOAN, rf"/api/v1/admin/loan-applications/{_MA_HO_SO_TRONG_PATH}/(review|explanation)"),
        ),
    ),
    ToolSpec(
        name="chinh_sach_ai",
        toolset=Toolset.ADMIN,
        description=(
            "Chính sách chấm điểm đang chạy: ngưỡng tự duyệt/tự từ chối, công thức và trọng số điểm, bảng "
            "hạng và hạn mức, luật đang bật. Đây là chính sách FINORA, không phải quy định pháp luật."
        ),
        params_model=NoParams,
        handler=_chinh_sach_ai,
        lane=Lane.PUBLIC,
        routes=(),
    ),
)

# Thứ tự khai báo là thứ tự hiển thị; registry lấy tối đa 4 gợi ý hợp màn. Trên LoanReview: tóm tắt,
# việc cần làm, KPI, chính sách. Trên màn khác (hoặc không có màn): việc cần làm, KPI, chính sách, hàng đợi.
ADMIN_SUGGESTIONS: tuple[SuggestionDef, ...] = (
    SuggestionDef(
        id="admin.review.summary",
        label="Tóm tắt hồ sơ này",
        toolset=Toolset.ADMIN,
        screens=frozenset({"LoanReview"}),
        tool="tom_tat_ho_so",
        params_from_context={"applicationNumber": "applicationNumber"},
        render=_render_tom_tat,
        keywords=("tóm tắt hồ sơ", "tóm tắt hồ sơ này", "tóm tắt giúp"),
    ),
    SuggestionDef(
        id="admin.todo.today",
        label="Hôm nay cần xử lý gì?",
        toolset=Toolset.ADMIN,
        screens=frozenset(),
        tool="viec_can_lam",
        params_from_context={},
        render=_render_viec,
        keywords=("cần xử lý", "việc cần làm", "cần làm gì", "việc tồn", "hàng đợi"),
    ),
    SuggestionDef(
        id="admin.kpi.overview",
        label="Tình hình danh mục cho vay?",
        toolset=Toolset.ADMIN,
        screens=frozenset(),
        tool="kpi_khoan_vay",
        params_from_context={},
        render=_render_kpi,
        keywords=("nợ xấu", "dư nợ", "tổng quan", "danh mục cho vay", "kpi", "npl"),
    ),
    SuggestionDef(
        id="admin.policy.thresholds",
        label="Ngưỡng tự duyệt và bảng hạng?",
        toolset=Toolset.ADMIN,
        screens=frozenset(),
        tool="chinh_sach_ai",
        params_from_context={},
        render=_render_chinh_sach,
        keywords=("ngưỡng", "tự duyệt", "tự động duyệt", "tự từ chối", "trọng số", "bảng hạng",
                  "duyệt tay", "vẫn chờ duyệt"),
    ),
    SuggestionDef(
        id="admin.queue.pending_review",
        label="Hồ sơ nào đang chờ thẩm định?",
        toolset=Toolset.ADMIN,
        screens=frozenset(),
        tool="danh_sach_ho_so",
        params_from_context={},
        render=_render_danh_sach,
        keywords=("hồ sơ chờ thẩm định", "danh sách chờ thẩm định", "bao nhiêu hồ sơ chờ"),
    ),
)

# Không có "Dashboard": web mở ngăn chat ở trang tổng quan nên câu nào cũng gửi screen=Dashboard; gắn KPI
# cho màn này thì đường B chạy trước C1/C2/C3 và mọi câu (kể cả câu chính sách) chỉ nhận số KPI
# (đo ở Task 21: "Vì sao hồ sơ 70 điểm vẫn phải duyệt tay?" nhận câu KPI danh mục).
ADMIN_SCREEN_TOOLS: dict[str, ScreenTool] = {
    # status tùy chọn (mặc định PENDING_REVIEW): tab "Tất cả" không gửi status mà vẫn phải đi đường B (fix-2);
    # đường B tự điền status từ màn khi có.
    "LoanList": ("danh_sach_ho_so", {}),
    "LoanReview": ("tom_tat_ho_so", {"applicationNumber": "applicationNumber"}),
    "Operations": ("viec_can_lam", {}),
}
