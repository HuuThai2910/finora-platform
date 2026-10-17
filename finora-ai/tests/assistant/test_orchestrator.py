"""Test bộ điều phối của trợ lý: ba đường A/B/C, từ chối khuyến nghị, vòng công cụ, chống bịa mã,
chặn PII, kiểm đầu ra, chế độ rút gọn, chống bấm hai lần, hết giờ và phiên hết hạn.

Công cụ và LLM đều là bản giả có kịch bản, nên test chạy không cần mạng; chỉ mục BM25 là bản thật
dựng từ vài đoạn kiến thức giả để đường "câu chính sách" chạy đúng như lúc thật.
"""

import asyncio
import copy
import json
import logging

import pytest
from pydantic import BaseModel, ConfigDict, Field

from app.api.errors import ApiError
from app.schemas.assistant import Card, CardItem, ChatRequest, ChatTurn, ScreenContext
from app.services.assistant.deeplinks import build_action
from app.services.assistant.guard import AN
from app.services.assistant.knowledge.bm25 import Bm25Index
from app.services.assistant.knowledge.store import KnowledgeChunk
from app.services.assistant.legal import LegalSource
from app.services.assistant.llm.providers import Lane
from app.services.assistant.llm.router import LlmResult, LlmUnavailable, ToolCall
from app.services.assistant.orchestrator import (
    CAN_CU_KHONG_TU_VAN,
    CAU_CHUA_DU_THONG_TIN,
    CAU_LOI_NOI_BO,
    CAU_QUA_LAU,
    CAU_TU_CHOI_KHUYEN_NGHI,
    DONG_RUT_GON,
    MIEN_TRU_DAU_TU,
    MIEN_TRU_PHAP_LY,
    NGUONG_BM25_CHINH_SACH,
    AssistantOrchestrator,
    match_intent,
)
from app.services.assistant.principal import Toolset
from app.services.assistant.prompts import NHAN_DU_LIEU
from app.services.assistant.relay import ServiceName
from app.services.assistant.tools.base import (
    NoParams,
    RouteRule,
    SuggestionDef,
    ToolError,
    ToolResult,
    ToolSpec,
)
from app.services.assistant.tools.registry import ToolRegistry

from .helpers import make_principal, make_settings, route_transport

MA_HO_SO = "LA-2026-000123"
NGUOI_VAY = frozenset({Toolset.BORROWER})
NHA_DAU_TU = frozenset({Toolset.INVESTOR})


# ── Công cụ giả ────────────────────────────────────────────────────────────────────────────────


class HoSoParams(BaseModel):
    model_config = ConfigDict(extra="forbid")
    applicationNumber: str = Field(pattern=r"^LA-\d{4}-\d{6}$")


class CongCuGia:
    """Bộ công cụ giả: mỗi handler trả ToolResult cố định và đếm số lần bị gọi."""

    def __init__(self, *, loi_chi_tiet: ToolError | None = None, facts_khoan_vay: dict | None = None,
                 loi_khoan_vay: Exception | None = None) -> None:
        self.da_goi: list[str] = []
        self.loi_chi_tiet = loi_chi_tiet
        self.loi_khoan_vay = loi_khoan_vay
        self.facts_khoan_vay = facts_khoan_vay or {"kyToi": "2.350.000 đ", "ngayDenHan": "25/10/2026"}

    async def ho_so_cua_toi(self, client, params) -> ToolResult:
        self.da_goi.append("ho_so_cua_toi")
        return ToolResult(
            facts={"hoSo": [{"applicationNumber": MA_HO_SO, "trangThai": "Đã duyệt", "soTien": "30.000.000 đ"}]},
            cards=[Card(kind="kv", title="Hồ sơ của tôi", items=[CardItem(label=MA_HO_SO, value="Đã duyệt")])],
            ids=frozenset({MA_HO_SO}),
        )

    async def chi_tiet_ho_so(self, client, params: HoSoParams) -> ToolResult:
        self.da_goi.append("chi_tiet_ho_so")
        if self.loi_chi_tiet is not None:
            raise self.loi_chi_tiet
        return ToolResult(
            facts={
                "applicationNumber": params.applicationNumber,
                "trangThai": "Đã duyệt",
                "goiVon": "Đang gọi vốn",
                "daGop": "12.000.000 đ",
                "deNghi": "30.000.000 đ",
            },
            cards=[Card(kind="progress", title="Tiến độ gọi vốn", value=12_000_000, max=30_000_000,
                        label="12.000.000 đ / 30.000.000 đ")],
            actions=[build_action("mo_ho_so", {"applicationNumber": params.applicationNumber})],
            ids=frozenset({params.applicationNumber}),
        )

    async def khoan_vay_cua_toi(self, client, params) -> ToolResult:
        self.da_goi.append("khoan_vay_cua_toi")
        if self.loi_khoan_vay is not None:
            raise self.loi_khoan_vay
        return ToolResult(facts=dict(self.facts_khoan_vay))

    async def trang_thai_pin(self, client, params) -> ToolResult:
        self.da_goi.append("trang_thai_pin")
        raise ToolError("PHIEN_HET_HAN", "Phiên đăng nhập đã hết hạn.")

    async def lenh_cua_toi(self, client, params) -> ToolResult:
        self.da_goi.append("lenh_cua_toi")
        # Mọi facts của nhà đầu tư mang cờ khong_tu_van (Task 10).
        return ToolResult(facts={
            "khong_tu_van": True,
            "lenh": [{"gia": "96%", "lyDoHuy": "Tránh tự khớp với lệnh bán của chính bạn"}],
        })


def _spec(ten, toolset, handler, params_model=NoParams, lane=Lane.USER_DATA) -> ToolSpec:
    return ToolSpec(
        name=ten, toolset=toolset, description=f"Công cụ giả {ten}", params_model=params_model, handler=handler,
        lane=lane, routes=(RouteRule("GET", ServiceName.LOAN, r"/api/v1/gia/.*"),),
    )


def _goi_y(cong_cu: CongCuGia) -> tuple[SuggestionDef, ...]:
    return (
        SuggestionDef(
            id="borrower.application.status", label="Hồ sơ của tôi đang thế nào?", toolset=Toolset.BORROWER,
            screens=frozenset({"ApplicationDetail"}), tool="chi_tiet_ho_so",
            params_from_context={"applicationNumber": "applicationNumber"},
            render=lambda kq: f"Hồ sơ {kq.facts['applicationNumber']} đang ở trạng thái {kq.facts['trangThai']}.",
            keywords=("trạng thái hồ sơ",),
        ),
        SuggestionDef(
            id="borrower.pin.locked", label="Vì sao PIN bị khóa?", toolset=Toolset.BORROWER,
            screens=frozenset(), tool="trang_thai_pin", params_from_context={},
            render=lambda kq: "PIN của bạn đang bị khóa.", keywords=("pin bị khóa",),
        ),
        SuggestionDef(
            id="investor.order.why_not_matched", label="Vì sao lệnh chưa khớp?", toolset=Toolset.INVESTOR,
            screens=frozenset({"MyBookOrders"}), tool="lenh_cua_toi", params_from_context={},
            render=lambda kq: f"Lệnh giá {kq.facts['lenh'][0]['gia']}: {kq.facts['lenh'][0]['lyDoHuy']}.",
            keywords=("lệnh chưa khớp", "lệnh bị hủy"),
        ),
    )


def _registry(cong_cu: CongCuGia) -> ToolRegistry:
    specs = [
        _spec("ho_so_cua_toi", Toolset.BORROWER, cong_cu.ho_so_cua_toi),
        _spec("chi_tiet_ho_so", Toolset.BORROWER, cong_cu.chi_tiet_ho_so, HoSoParams),
        _spec("khoan_vay_cua_toi", Toolset.BORROWER, cong_cu.khoan_vay_cua_toi),
        _spec("trang_thai_pin", Toolset.BORROWER, cong_cu.trang_thai_pin),
        _spec("lenh_cua_toi", Toolset.INVESTOR, cong_cu.lenh_cua_toi),
    ]
    man = {
        "ApplicationDetail": ("chi_tiet_ho_so", {"applicationNumber": "applicationNumber"}),
        "LoanServicingDetail": ("khoan_vay_cua_toi", {}),
    }
    return ToolRegistry(specs, _goi_y(cong_cu), man)


# ── Kho kiến thức giả ──────────────────────────────────────────────────────────────────────────


def _doan(id_, title, roles, kind, body, keywords, legal_refs=()) -> KnowledgeChunk:
    return KnowledgeChunk(
        id=id_, title=title, roles=frozenset(roles), kind=kind, body=body, legal_refs=tuple(legal_refs),
        source="test", version="TEST_V1", checked_at="2026-10-15", keywords=tuple(keywords),
    )


DOAN_KY_HAN = _doan(
    "PL-KY-HAN-01", "Kỳ hạn vay tối đa", {"ALL"}, "LUAT",
    "Hợp đồng vay ngang hàng trong cơ chế thử nghiệm có thời hạn không quá 02 năm, vì vậy FINORA chỉ cho vay "
    "kỳ hạn tối đa 24 tháng.",
    ("kỳ hạn tối đa", "vay tối đa bao lâu", "24 tháng"),
    legal_refs=("LAW-P2P-2025:Điều 11 khoản 1 điểm c",),
)
CAC_DOAN = [
    DOAN_KY_HAN,
    _doan("NV-TAT-TOAN-01", "Phí tất toán trước hạn", {"BORROWER"}, "CHINH_SACH",
          "Tất toán trước hạn là trả hết nợ gốc còn lại trước ngày đáo hạn; phí theo biểu phí FINORA.",
          ("tất toán", "trả hết nợ")),
    _doan("NDT-COMMITMENT-01", "Commitment và Note", {"INVESTOR"}, "HUONG_DAN",
          "Commitment là cam kết góp vốn khi khoản vay đang gọi vốn; Note là phần nợ nhận được sau giải ngân.",
          ("commitment", "note")),
    _doan("QT-NGUONG-01", "Ngưỡng tự duyệt", {"ADMIN"}, "CHINH_SACH",
          "Hồ sơ từ ngưỡng tự duyệt trở lên được duyệt tự động; vùng giữa chờ thẩm định thủ công.",
          ("tự duyệt", "ngưỡng")),
    _doan("CH-EKYC-01", "Xác minh danh tính eKYC", {"ALL"}, "HUONG_DAN",
          "eKYC là bước chụp giấy tờ và khuôn mặt để xác minh danh tính trước khi vay hoặc đầu tư.",
          ("ekyc", "xác minh danh tính")),
    _doan("CH-VI-01", "Ví FINORA", {"ALL"}, "HUONG_DAN",
          "Ví FINORA giữ số dư khả dụng và số tiền đang bị giữ cho lệnh hoặc cam kết chưa hoàn tất.",
          ("ví", "số dư")),
]
NGUON_PHAP_LY = {
    "LAW-P2P-2025": LegalSource(
        id="LAW-P2P-2025", title="Nghị định 94/2025/NĐ-CP", url="https://vbpl.vn/nd-94-2025",
        status="CURRENT", checked_at="2026-10-10",
    )
}


# ── LLM giả có kịch bản ────────────────────────────────────────────────────────────────────────


class KichBanLlm:
    """LlmRouter giả: mỗi lần complete() lấy bước kế tiếp trong kịch bản (kết quả, ngoại lệ hoặc coroutine)."""

    def __init__(self, *buoc) -> None:
        self._buoc = list(buoc)
        self.calls: list[dict] = []

    async def complete(self, messages, *, lane, tools=None, max_tokens=800, pin_provider=None, user_hash=None):
        self.calls.append({"messages": copy.deepcopy(messages), "lane": lane, "tools": tools, "user_hash": user_hash})
        assert self._buoc, "LLM bị gọi nhiều hơn kịch bản"
        buoc = self._buoc.pop(0)
        if isinstance(buoc, Exception):
            raise buoc
        if callable(buoc):
            return await buoc()
        return buoc


def tra_loi(answer: str, actions=()) -> LlmResult:
    return LlmResult(
        text=json.dumps({"answer": answer, "actions": list(actions)}, ensure_ascii=False), tool_calls=[],
        provider="groq_gptoss120b", model="openai/gpt-oss-120b", prompt_tokens=100, completion_tokens=20,
    )


def goi(*calls: tuple[str, str, dict]) -> LlmResult:
    return LlmResult(
        text=None, tool_calls=[ToolCall(id=i, name=n, arguments=a) for i, n, a in calls],
        provider="groq_gptoss120b", model="openai/gpt-oss-120b", prompt_tokens=80, completion_tokens=10,
    )


class DongHo:
    def __init__(self) -> None:
        self.bay_gio = 1_000_000.0

    def __call__(self) -> float:
        return self.bay_gio


def tao(llm: KichBanLlm, cong_cu: CongCuGia | None = None, dong_ho: DongHo | None = None, **cai_dat):
    cong_cu = cong_cu or CongCuGia()
    orchestrator = AssistantOrchestrator(
        make_settings(**cai_dat), _registry(cong_cu), Bm25Index(CAC_DOAN), llm, NGUON_PHAP_LY,
        transport=route_transport({}), clock=dong_ho or DongHo(), intents=_goi_y(cong_cu),
    )
    return orchestrator, cong_cu


def hoi(message, *, screen=None, params=None, suggestion_id=None, history=(), surface="mobile") -> ChatRequest:
    context = ScreenContext(screen=screen, params=params or {}) if screen or params else None
    return ChatRequest(
        message=message, history=[ChatTurn(role=r, content=c) for r, c in history], surface=surface,
        context=context, suggestion_id=suggestion_id,
    )


def _ban_ghi_log(caplog) -> list[dict]:
    return [
        json.loads(r.getMessage().split(" ", 1)[1])
        for r in caplog.records
        if r.name == "finora.assistant.audit" and r.getMessage().startswith("assistant_turn ")
    ]


# ── Đường A: nút gợi ý ─────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_duong_a_goi_y_dung_cau_mau_khong_goi_llm():
    llm = KichBanLlm()
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Hồ sơ của tôi đang thế nào?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO},
            suggestion_id="borrower.application.status"),
    )

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer == f"Hồ sơ {MA_HO_SO} đang ở trạng thái Đã duyệt."
    assert llm.calls == []
    assert cong_cu.da_goi == ["chi_tiet_ho_so"]
    assert [a.key for a in phan_hoi.actions] == ["mo_ho_so"]
    assert phan_hoi.actions[0].target.params == {"applicationNumber": MA_HO_SO}
    assert phan_hoi.cards[0].kind == "progress"
    assert "borrower.application.status" not in [s.id for s in phan_hoi.suggestions]
    assert phan_hoi.disclaimer is None


@pytest.mark.asyncio
async def test_goi_y_cua_vai_tro_khac_bi_bo_qua():
    llm = KichBanLlm(tra_loi("Mình chưa thấy lệnh nào."))
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Vì sao lệnh chưa khớp?", suggestion_id="investor.order.why_not_matched"),
    )

    assert "lenh_cua_toi" not in cong_cu.da_goi
    assert phan_hoi.mode == "llm"


# ── Từ chối khuyến nghị ────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_nha_dau_tu_xin_khuyen_nghi_bi_tu_choi_bang_cau_mau():
    llm = KichBanLlm()
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_INVESTOR"), NHA_DAU_TU, hoi("Nên đầu tư khoản nào an toàn nhất?"),
    )

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer == CAU_TU_CHOI_KHUYEN_NGHI
    assert phan_hoi.disclaimer == MIEN_TRU_DAU_TU
    assert llm.calls == []
    assert cong_cu.da_goi == []


# ── Đường B: câu gắn màn hình ──────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_duong_b_lay_du_lieu_man_hinh_roi_goi_llm_mot_lan_khong_kem_cong_cu():
    cau = "Hồ sơ đã được duyệt nhưng mới gọi được 12.000.000 đ trên 30.000.000 đ."
    llm = KichBanLlm(tra_loi(cau, ["mo_ho_so", "khoa_khong_co_that"]))
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Đã duyệt sao chưa nhận tiền?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO},
            history=[("user", "Chào"), ("assistant", "Chào bạn")]),
    )

    assert phan_hoi.mode == "llm"
    assert phan_hoi.answer == cau
    assert cong_cu.da_goi == ["chi_tiet_ho_so"]
    assert len(llm.calls) == 1
    assert llm.calls[0]["tools"] is None
    assert llm.calls[0]["lane"] == Lane.USER_DATA
    tin_cuoi = llm.calls[0]["messages"][-1]["content"]
    assert NHAN_DU_LIEU in tin_cuoi
    assert "12.000.000 đ" in tin_cuoi
    assert tin_cuoi.endswith("CÂU HỎI: Đã duyệt sao chưa nhận tiền?")
    assert [m["role"] for m in llm.calls[0]["messages"]] == ["system", "user", "assistant", "user"]
    # Nút trùng (công cụ + LLM cùng chọn mo_ho_so) chỉ hiện một lần; khóa lạ bị bỏ.
    assert [a.key for a in phan_hoi.actions] == ["mo_ho_so"]
    # Kho giả không có đoạn nào đủ điểm cho câu này: không kèm kiến thức, không gắn căn cứ lạc đề.
    assert '"kien_thuc"' not in tin_cuoi
    assert phan_hoi.citations == []
    # Băm user để OpenRouter nhận `user: <hash>`, không bao giờ là user id thật.
    assert len(llm.calls[0]["user_hash"]) == 64


@pytest.mark.asyncio
async def test_duong_b_guard_bat_so_lieu_bia_thi_dung_cau_mau_cua_goi_y(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    llm = KichBanLlm(tra_loi("Bạn còn thiếu 18.000.000 đ nữa là đủ vốn."))
    orchestrator, _ = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Còn thiếu bao nhiêu?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO}),
    )

    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer == f"Hồ sơ {MA_HO_SO} đang ở trạng thái Đã duyệt."
    assert phan_hoi.cards  # thẻ số liệu thật vẫn hiện
    assert _ban_ghi_log(caplog)[-1]["errorCode"] == "GUARD_SO_LIEU"


@pytest.mark.asyncio
async def test_pii_trong_du_kien_thi_khong_goi_llm():
    llm = KichBanLlm()
    cong_cu = CongCuGia(facts_khoan_vay={"kyToi": "2.350.000 đ", "lienHe": "0912345678"})
    orchestrator, _ = tao(llm, cong_cu)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Kỳ tới trả bao nhiêu?", screen="LoanServicingDetail"),
    )

    assert llm.calls == []
    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer.startswith("Thông tin:")
    assert "2.350.000 đ" in phan_hoi.answer
    assert "0912345678" not in phan_hoi.answer


@pytest.mark.asyncio
async def test_llm_khong_kha_dung_thi_che_do_rut_gon(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    llm = KichBanLlm(LlmUnavailable("hết hạn mức", attempts=[("groq_gptoss120b", "HET_HAN_MUC")]))
    orchestrator, _ = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO}),
    )

    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer == f"{DONG_RUT_GON}\nHồ sơ {MA_HO_SO} đang ở trạng thái Đã duyệt."
    assert phan_hoi.cards
    assert _ban_ghi_log(caplog)[-1]["errorCode"] == "LLM_HET_HAN_MUC"


# ── Đường C ────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_duong_c_khop_tu_khoa_thanh_cau_mau():
    llm = KichBanLlm()
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(make_principal("ROLE_INVESTOR"), NHA_DAU_TU, hoi("Sao lệnh chưa khớp vậy?"))

    assert phan_hoi.mode == "template"
    assert phan_hoi.answer == "Lệnh giá 96%: Tránh tự khớp với lệnh bán của chính bạn."
    assert cong_cu.da_goi == ["lenh_cua_toi"]
    assert llm.calls == []
    assert phan_hoi.disclaimer == MIEN_TRU_DAU_TU
    assert [c.id for c in phan_hoi.citations] == [CAN_CU_KHONG_TU_VAN.id]
    assert phan_hoi.citations[0].kind == "HUONG_DAN"


@pytest.mark.asyncio
async def test_cau_chinh_sach_di_lan_public_mot_lan_kem_can_cu_luat():
    cau_hoi = "Vì sao kỳ hạn vay tối đa chỉ 24 tháng?"
    assert Bm25Index(CAC_DOAN).search(cau_hoi, toolsets=frozenset({"BORROWER"}))[0][1] >= NGUONG_BM25_CHINH_SACH
    llm = KichBanLlm(tra_loi("Hợp đồng vay trong cơ chế thử nghiệm không quá 02 năm nên FINORA cho vay tối đa 24 tháng."))
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi(cau_hoi, history=[("assistant", "Kỳ tới bạn trả 2.350.000 đ")]),
    )

    assert phan_hoi.mode == "llm"
    assert cong_cu.da_goi == []
    assert len(llm.calls) == 1
    assert llm.calls[0]["lane"] == Lane.PUBLIC
    assert llm.calls[0]["tools"] is None
    # Làn PUBLIC có thể tới nhà cung cấp dùng dữ liệu để huấn luyện: không gửi lịch sử (có số liệu cá nhân).
    assert "2.350.000" not in json.dumps(llm.calls[0]["messages"], ensure_ascii=False)
    luat = [c for c in phan_hoi.citations if c.kind == "LUAT"]
    assert luat and luat[0].url == "https://vbpl.vn/nd-94-2025"
    assert "Điều 11" in luat[0].label
    assert phan_hoi.disclaimer == MIEN_TRU_PHAP_LY


@pytest.mark.asyncio
async def test_guard_bat_trich_dan_tu_viet_thi_dung_doan_kien_thuc():
    llm = KichBanLlm(tra_loi("Theo Điều 99 Luật Đất đai, kỳ hạn tối đa là 24 tháng."))
    orchestrator, _ = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Vì sao kỳ hạn vay tối đa chỉ 24 tháng?"),
    )

    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer == DOAN_KY_HAN.body


@pytest.mark.asyncio
async def test_vong_lap_cong_cu_hai_vong_roi_tra_loi():
    llm = KichBanLlm(
        goi(("c1", "ho_so_cua_toi", {})),
        goi(("c2", "chi_tiet_ho_so", {"applicationNumber": MA_HO_SO})),
        tra_loi(f"Hồ sơ {MA_HO_SO} đã được góp 12.000.000 đ trên 30.000.000 đ.", ["mo_ho_so"]),
    )
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Hồ sơ mới nhất của tôi được góp bao nhiêu rồi?"),
    )

    assert phan_hoi.mode == "llm"
    assert cong_cu.da_goi == ["ho_so_cua_toi", "chi_tiet_ho_so"]
    assert len(llm.calls) == 3
    assert all(c["lane"] == Lane.USER_DATA for c in llm.calls)
    ten_cong_cu = {t["function"]["name"] for t in llm.calls[0]["tools"]}
    assert ten_cong_cu == {"ho_so_cua_toi", "chi_tiet_ho_so", "khoan_vay_cua_toi", "trang_thai_pin"}
    tin_vong_3 = llm.calls[2]["messages"]
    assert [m.get("tool_call_id") for m in tin_vong_3 if m["role"] == "tool"] == ["c1", "c2"]
    assert NHAN_DU_LIEU in tin_vong_3[-1]["content"]
    assert [a.key for a in phan_hoi.actions] == ["mo_ho_so"]
    assert len(phan_hoi.cards) == 2


@pytest.mark.asyncio
async def test_duong_cong_cu_kem_doan_kien_thuc_lien_quan():
    llm = KichBanLlm(tra_loi("Phí tất toán của bạn tính theo biểu phí FINORA."))
    orchestrator, _ = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Phí tất toán trước hạn của tôi là bao nhiêu?"),
    )

    assert llm.calls[0]["tools"] is not None
    assert llm.calls[0]["lane"] == Lane.USER_DATA
    tin_dau = llm.calls[0]["messages"][-1]["content"]
    assert NHAN_DU_LIEU in tin_dau and "NV-TAT-TOAN-01" in tin_dau
    assert [c.id for c in phan_hoi.citations] == ["NV-TAT-TOAN-01"]
    assert phan_hoi.mode == "llm"


@pytest.mark.asyncio
async def test_ma_bi_bia_khong_duoc_chay_va_bao_loi_cho_llm():
    llm = KichBanLlm(
        goi(("c1", "chi_tiet_ho_so", {"applicationNumber": "LA-2026-999999"})),
        tra_loi("Mình chưa thấy hồ sơ nào của bạn khớp mã đó."),
    )
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Hồ sơ của tôi bị gì vậy?"),
    )

    assert cong_cu.da_goi == []
    tin_tool = llm.calls[1]["messages"][-1]
    assert tin_tool["role"] == "tool" and "không có trong dữ liệu" in tin_tool["content"]
    assert phan_hoi.mode == "llm"


@pytest.mark.asyncio
async def test_ma_nguoi_dung_tu_go_thi_duoc_dung():
    llm = KichBanLlm(
        goi(("c1", "chi_tiet_ho_so", {"applicationNumber": MA_HO_SO})),
        tra_loi("Hồ sơ đã được duyệt."),
    )
    orchestrator, cong_cu = tao(llm)

    await orchestrator.handle(make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi(f"Hồ sơ {MA_HO_SO} của tôi sao rồi?"))

    assert cong_cu.da_goi == ["chi_tiet_ho_so"]


@pytest.mark.asyncio
async def test_sai_tham_so_hai_lan_thi_dung_cau_du_phong():
    llm = KichBanLlm(
        goi(("c1", "chi_tiet_ho_so", {"applicationNumber": "abc"})),
        goi(("c2", "chi_tiet_ho_so", {})),
    )
    orchestrator, cong_cu = tao(llm)

    phan_hoi = await orchestrator.handle(make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Hồ sơ của tôi thế nào?"))

    assert len(llm.calls) == 2
    assert "Tham số không hợp lệ" in llm.calls[1]["messages"][-1]["content"]
    assert cong_cu.da_goi == []
    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer == CAU_CHUA_DU_THONG_TIN


@pytest.mark.asyncio
async def test_cau_hoi_co_pii_duoc_che_truoc_khi_gui_llm():
    llm = KichBanLlm(tra_loi("Mình không cần số điện thoại của bạn."))
    orchestrator, _ = tao(llm)

    await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("SĐT của tôi là 0912345678, hồ sơ của tôi thế nào?"),
    )

    goi_di = json.dumps(llm.calls[0]["messages"], ensure_ascii=False)
    assert "0912345678" not in goi_di
    assert AN in goi_di


# ── Lỗi, hết giờ, chống bấm hai lần, log ───────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_phien_het_han_o_service_dich_thanh_401(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    orchestrator, _ = tao(KichBanLlm())

    with pytest.raises(ApiError) as loi:
        await orchestrator.handle(
            make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Vì sao PIN bị khóa?", suggestion_id="borrower.pin.locked"),
        )

    assert loi.value.status_code == 401
    assert loi.value.code == "ASSISTANT_UNAUTHENTICATED"
    ban_ghi = _ban_ghi_log(caplog)[-1]
    assert ban_ghi["mode"] == "error" and ban_ghi["errorCode"] == "PHIEN_HET_HAN"


@pytest.mark.asyncio
async def test_service_loi_thi_bao_va_dua_nut_mo_man():
    cong_cu = CongCuGia(loi_chi_tiet=ToolError("DICH_VU_LOI", "Chưa lấy được dữ liệu hồ sơ, bạn thử lại sau."))
    orchestrator, _ = tao(KichBanLlm(), cong_cu)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO},
            suggestion_id="borrower.application.status"),
    )

    assert phan_hoi.answer == "Chưa lấy được dữ liệu hồ sơ, bạn thử lại sau."
    assert phan_hoi.mode == "template"
    assert [a.key for a in phan_hoi.actions] == ["mo_ho_so"]
    assert phan_hoi.actions[0].target.params == {"applicationNumber": MA_HO_SO}


@pytest.mark.asyncio
async def test_bam_hai_lan_tra_lai_phan_hoi_cu_trong_60_giay():
    dong_ho = DongHo()
    llm = KichBanLlm(tra_loi("Hồ sơ đã được duyệt."), tra_loi("Hồ sơ đã được duyệt."))
    orchestrator, _ = tao(llm, dong_ho=dong_ho)
    yeu_cau = hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO})
    nguoi = make_principal("ROLE_BORROWER")

    lan_1 = await orchestrator.handle(nguoi, NGUOI_VAY, yeu_cau)
    lan_2 = await orchestrator.handle(nguoi, NGUOI_VAY, yeu_cau)
    assert lan_2.turn_id == lan_1.turn_id
    assert len(llm.calls) == 1

    dong_ho.bay_gio += 61
    lan_3 = await orchestrator.handle(nguoi, NGUOI_VAY, yeu_cau)
    assert lan_3.turn_id != lan_1.turn_id
    assert len(llm.calls) == 2


@pytest.mark.asyncio
async def test_qua_thoi_gian_luot_thi_tra_cau_qua_lau(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")

    async def cham():
        await asyncio.sleep(5)
        return tra_loi("không bao giờ tới")

    orchestrator, _ = tao(KichBanLlm(cham), turn_timeout_s=0.05)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO}),
    )

    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer == CAU_QUA_LAU
    assert _ban_ghi_log(caplog)[-1]["errorCode"] == "TURN_TIMEOUT"


@pytest.mark.asyncio
async def test_moi_luot_deu_ghi_log_khong_co_noi_dung(caplog):
    caplog.set_level(logging.INFO, logger="finora.assistant.audit")
    orchestrator, _ = tao(KichBanLlm(tra_loi("Hồ sơ đã được duyệt.")))

    await orchestrator.handle(make_principal("ROLE_INVESTOR"), NHA_DAU_TU, hoi("Sao lệnh chưa khớp vậy?"))
    await orchestrator.handle(
        make_principal("ROLE_BORROWER", user_id="27"), NGUOI_VAY,
        hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO}),
    )

    ban_ghi = _ban_ghi_log(caplog)
    assert [b["mode"] for b in ban_ghi] == ["template", "llm"]
    assert ban_ghi[1]["tools"] == ["chi_tiet_ho_so"]
    assert ban_ghi[1]["provider"] == "groq_gptoss120b"
    assert ban_ghi[1]["promptTokens"] == 100
    toan_bo = " ".join(r.getMessage() for r in caplog.records)
    assert "Hồ sơ sao rồi" not in toan_bo and "đã được duyệt" not in toan_bo
    assert '"user":"27"' not in toan_bo


@pytest.mark.asyncio
async def test_hai_lan_bam_cung_luc_chi_xu_ly_mot_lan():
    async def cham():
        await asyncio.sleep(0.05)
        return tra_loi("Hồ sơ đã được duyệt.")

    llm = KichBanLlm(cham)
    orchestrator, cong_cu = tao(llm)
    yeu_cau = hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO})
    nguoi = make_principal("ROLE_BORROWER")

    lan_1, lan_2 = await asyncio.gather(
        orchestrator.handle(nguoi, NGUOI_VAY, yeu_cau), orchestrator.handle(nguoi, NGUOI_VAY, yeu_cau)
    )

    assert lan_1.turn_id == lan_2.turn_id
    assert len(llm.calls) == 1
    assert cong_cu.da_goi == ["chi_tiet_ho_so"]


@pytest.mark.asyncio
async def test_cong_cu_loi_trong_vong_llm_thi_dung_va_bao_loi():
    cong_cu = CongCuGia(loi_chi_tiet=ToolError("KHONG_TIM_THAY", "Không tìm thấy dữ liệu bạn hỏi."))
    llm = KichBanLlm(goi(("c1", "chi_tiet_ho_so", {"applicationNumber": MA_HO_SO})))
    orchestrator, _ = tao(llm, cong_cu)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi(f"Hồ sơ {MA_HO_SO} của tôi sao rồi?"),
    )

    # Lỗi service không được đưa cho LLM diễn giải: câu cố định. Không tìm thấy (403/404) thì không kèm nút mở lại
    # đúng hồ sơ đó — người hỏi không xem được nó (fix-2; chạy thật: nút trỏ vào hồ sơ của người khác).
    assert len(llm.calls) == 1
    assert phan_hoi.answer == "Không tìm thấy dữ liệu bạn hỏi."
    assert phan_hoi.mode == "fallback"
    assert phan_hoi.actions == []


@pytest.mark.asyncio
async def test_loi_noi_bo_van_tra_loi_va_khong_log_noi_dung(caplog):
    caplog.set_level(logging.INFO)
    cong_cu = CongCuGia(loi_khoan_vay=KeyError("lien_he 0912345678"))
    orchestrator, _ = tao(KichBanLlm(), cong_cu)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY, hoi("Kỳ tới trả bao nhiêu?", screen="LoanServicingDetail"),
    )

    assert phan_hoi.mode == "fallback"
    assert phan_hoi.answer == CAU_LOI_NOI_BO
    assert _ban_ghi_log(caplog)[-1]["errorCode"] == "LOI_NOI_BO"
    assert "0912345678" not in caplog.text
    assert "KeyError" in caplog.text


@pytest.mark.asyncio
async def test_nut_do_llm_chon_lay_tham_so_tu_man_dang_mo():
    ma_khoan = "LN-2026-000123"
    llm = KichBanLlm(tra_loi("Kỳ tới bạn trả 2.350.000 đ vào 25/10/2026.", ["tra_no", "mo_tham_dinh"]))
    orchestrator, _ = tao(llm)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Kỳ tới trả bao nhiêu?", screen="LoanServicingDetail", params={"loanNumber": ma_khoan}),
    )

    assert phan_hoi.mode == "llm"
    tin_cuoi = llm.calls[0]["messages"][-1]["content"]
    assert f"MÀN ĐANG MỞ: LoanServicingDetail (loanNumber={ma_khoan})" in tin_cuoi
    # "mo_tham_dinh" là nút của web: bị bỏ trên mobile dù LLM có chọn.
    assert [a.key for a in phan_hoi.actions] == ["tra_no"]
    assert phan_hoi.actions[0].target.params == {"loanNumber": ma_khoan}


def test_match_intent_chon_goi_y_khop_nhieu_tu_khoa_nhat():
    goi_y = _goi_y(CongCuGia())
    assert match_intent("lenh bi huy va lenh chua khop", goi_y, NHA_DAU_TU).id == "investor.order.why_not_matched"
    assert match_intent("Lệnh chưa khớp", goi_y, NGUOI_VAY) is None
    assert match_intent("Xin chào", goi_y, NGUOI_VAY | NHA_DAU_TU) is None


def test_match_intent_hoa_diem_thi_uu_tien_goi_y_cua_man_dang_mo():
    def _g(id_, screens):
        return SuggestionDef(id=id_, label=id_, toolset=Toolset.BORROWER, screens=frozenset(screens),
                             tool="ho_so_cua_toi", params_from_context={}, render=lambda kq: "", keywords=(
                                 "hồ sơ của tôi" if id_ == "a" else "bị từ chối",))

    goi_y = [_g("a", {"Home"}), _g("b", {"ApplicationDetail"})]
    cau = "Vì sao hồ sơ của tôi bị từ chối?"
    assert match_intent(cau, goi_y, NGUOI_VAY).id == "a"
    assert match_intent(cau, goi_y, NGUOI_VAY, "ApplicationDetail").id == "b"


def test_suggestions_theo_vai_tro_va_man_hinh():
    orchestrator, _ = tao(KichBanLlm())
    assert [s.id for s in orchestrator.suggestions(NGUOI_VAY, "ApplicationDetail")] == [
        "borrower.application.status", "borrower.pin.locked",
    ]
    assert [s.id for s in orchestrator.suggestions(NHA_DAU_TU, "MyBookOrders")] == ["investor.order.why_not_matched"]
