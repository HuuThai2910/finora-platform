"""Ba mối lo còn mở của Task 13–15 (fix-1, mục 4).

(a) Công cụ của màn đang mở lỗi thì câu trả lời vẫn dịch `context.lastErrorCode` (trước đây chỉ còn câu
    "chưa lấy được dữ liệu", mất luôn lời giải thích lỗi người dùng vừa gặp).
(b) Dựng orchestrator lỗi lúc nhận request (kho kiến thức hỏng…) trả JSON có mã 503 ASSISTANT_UNAVAILABLE
    như mọi lỗi khác của trợ lý (spec mục 5), không phải HTTP 500 trần.
(c) Guard khuyến nghị không thay câu hướng dẫn trung lập ("bạn nên chọn tiêu chí lọc…") bằng câu mẫu, nhưng
    vẫn bắt lời khuyên thật ("nên đầu tư khoản X", "khoản này an toàn nhất").
"""

import pytest
from fastapi.testclient import TestClient

from app.api.assistant_router import get_user_rate_limiter
from app.api.errors import ApiError
from app.services.assistant import orchestrator as mo_dun_orchestrator
from app.services.assistant.glossary import dich_ma_loi
from app.services.assistant.guard import check_reply
from app.services.assistant.llm.quota import UserRateLimiter
from app.services.assistant.orchestrator import get_orchestrator
from app.services.assistant.settings import get_settings
from app.services.assistant.tools.base import ToolError
from app.services.auth.keycloak_jwt import get_verifier
from main import app

from .helpers import make_principal, make_settings
from .test_orchestrator import MA_HO_SO, NGUOI_VAY, CongCuGia, KichBanLlm, hoi, tao

# ── (a) công cụ màn hình lỗi vẫn giải thích mã lỗi vừa gặp ─────────────────────────────────────


@pytest.mark.asyncio
async def test_cong_cu_man_hinh_loi_van_giai_thich_ma_loi_vua_gap():
    cong_cu = CongCuGia(loi_chi_tiet=ToolError("DICH_VU_LOI", "Chưa lấy được dữ liệu hồ sơ, bạn thử lại sau."))
    orchestrator, _ = tao(KichBanLlm(), cong_cu)
    yeu_cau = hoi("Sao tôi không ký được?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO})
    yeu_cau.context.last_error_code = "PIN_LOCKED"

    phan_hoi = await orchestrator.handle(make_principal("ROLE_BORROWER"), NGUOI_VAY, yeu_cau)

    assert phan_hoi.answer.startswith("Chưa lấy được dữ liệu hồ sơ, bạn thử lại sau.")
    assert dich_ma_loi("PIN_LOCKED") in phan_hoi.answer
    assert "PIN_LOCKED" not in phan_hoi.answer  # không nhắc lại mã kỹ thuật
    assert [a.key for a in phan_hoi.actions] == ["mo_ho_so"]


@pytest.mark.asyncio
async def test_cong_cu_loi_khong_co_ma_loi_thi_giu_nguyen_cau_bao_loi():
    cong_cu = CongCuGia(loi_chi_tiet=ToolError("DICH_VU_LOI", "Chưa lấy được dữ liệu hồ sơ, bạn thử lại sau."))
    orchestrator, _ = tao(KichBanLlm(), cong_cu)

    phan_hoi = await orchestrator.handle(
        make_principal("ROLE_BORROWER"), NGUOI_VAY,
        hoi("Hồ sơ sao rồi?", screen="ApplicationDetail", params={"applicationNumber": MA_HO_SO}),
    )

    assert phan_hoi.answer == "Chưa lấy được dữ liệu hồ sơ, bạn thử lại sau."


# ── (b) dựng orchestrator lỗi lúc nhận request → 503 có mã ─────────────────────────────────────


def test_get_orchestrator_loi_thi_nem_api_error_503(monkeypatch):
    def hong():
        raise ValueError("chung.yaml: trùng id")

    monkeypatch.setattr(mo_dun_orchestrator, "_dung_orchestrator", hong)

    with pytest.raises(ApiError) as loi:
        get_orchestrator()

    assert loi.value.status_code == 503 and loi.value.code == "ASSISTANT_UNAVAILABLE"


@pytest.fixture
def client_khong_dung_duoc_orchestrator(monkeypatch):
    def hong():
        raise ValueError("chung.yaml: trùng id")

    class Verifier:
        def verify(self, token):
            return make_principal("ROLE_BORROWER")

    monkeypatch.setattr(mo_dun_orchestrator, "_dung_orchestrator", hong)
    app.dependency_overrides[get_settings] = lambda: make_settings()
    app.dependency_overrides[get_verifier] = lambda: Verifier()
    app.dependency_overrides[get_user_rate_limiter] = lambda: UserRateLimiter(per_minute=10, per_day=100)
    yield TestClient(app, raise_server_exceptions=False)
    app.dependency_overrides.clear()


def test_chat_khi_khong_dung_duoc_tro_ly_tra_503_co_ma(client_khong_dung_duoc_orchestrator):
    res = client_khong_dung_duoc_orchestrator.post(
        "/api/v1/ai/assistant/chat", json={"message": "Chào", "surface": "mobile"},
        headers={"Authorization": "Bearer tok"},
    )

    assert res.status_code == 503
    assert res.json()["code"] == "ASSISTANT_UNAVAILABLE"


def test_suggestions_khi_khong_dung_duoc_tro_ly_tra_503_co_ma(client_khong_dung_duoc_orchestrator):
    res = client_khong_dung_duoc_orchestrator.get(
        "/api/v1/ai/assistant/suggestions", params={"surface": "mobile"}, headers={"Authorization": "Bearer tok"},
    )

    assert res.status_code == 503
    assert res.json()["code"] == "ASSISTANT_UNAVAILABLE"


# ── (c) guard khuyến nghị: không báo động giả với câu hướng dẫn trung lập ───────────────────────


def _khuyen_nghi(cau: str) -> bool:
    return "KHUYEN_NGHI" in check_reply(cau, fact_texts=[cau], citations=[], investor=True).reasons


@pytest.mark.parametrize(
    "cau",
    [
        "Bạn nên chọn tiêu chí lọc như hạng, lãi suất, kỳ hạn rồi mình liệt kê các khoản khớp.",
        "Bạn nên chọn mục Lọc ở góc phải màn Sàn.",
        "Trước khi mua, bạn nên đọc kỹ hồ sơ người vay.",
        "Bạn nên mua theo khả năng chịu rủi ro của chính mình.",
        "Muốn bán Note, bạn nên chọn giá trên sổ lệnh theo ý mình; mình không gợi ý giá.",
    ],
)
def test_cau_huong_dan_trung_lap_khong_bi_coi_la_khuyen_nghi(cau):
    assert not _khuyen_nghi(cau)


@pytest.mark.parametrize(
    "cau",
    [
        "Bạn nên đầu tư khoản #31.",
        "Bạn nên chọn khoản 14 vì lãi cao.",
        "Nên mua Note của khoản này ngay.",
        "Bạn nên bán Note này trước khi nợ xấu.",
        "Khoản này an toàn nhất.",
        "Khoản #31 là khoản lời nhất hiện nay.",
        "Bạn nên chọn hạng A cho chắc.",
        "Nên rót vốn vào khoản 12.",
    ],
)
def test_loi_khuyen_that_van_bi_bat(cau):
    assert _khuyen_nghi(cau)
