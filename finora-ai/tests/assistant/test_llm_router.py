"""LlmRouter: chọn nhà theo làn, chuyển nhà khi lỗi, ngắt tạm, hạn mức, parse tool call.

Mọi nhà cung cấp là giả (httpx.MockTransport); mỗi host ``<id>.test`` có một kịch bản phản hồi.
"""

import asyncio
import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

import httpx
import pytest

from app.services.assistant.llm import router as router_module
from app.services.assistant.llm.providers import Lane, ProviderConfig
from app.services.assistant.llm.router import (
    LlmRouter,
    LlmUnavailable,
    ToolCall,
    estimate_tokens,
    get_llm_router,
)

from .helpers import make_settings

HAI_LAN = frozenset({Lane.USER_DATA, Lane.PUBLIC})
CHI_PUBLIC = frozenset({Lane.PUBLIC})
CAU_HOI = [{"role": "user", "content": "Hồ sơ LA-2026-000123 sao chưa giải ngân?"}]
TOOLS = [{
    "type": "function",
    "function": {
        "name": "chi_tiet_ho_so",
        "description": "Lấy chi tiết hồ sơ",
        "parameters": {"type": "object", "properties": {"applicationNumber": {"type": "string"}}},
    },
}]


class FakeClock:
    def __init__(self) -> None:
        self.now = datetime(2026, 10, 15, 8, 0, tzinfo=timezone.utc).timestamp()

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def nha(provider_id: str, *, lanes=HAI_LAN, **overrides: Any) -> ProviderConfig:
    values: dict[str, Any] = {
        "id": provider_id,
        "base_url": f"https://{provider_id}.test/v1",
        "model": f"model-{provider_id}",
        "api_key_env": f"KEY_{provider_id.upper()}",
        "lanes": lanes,
        "rpm": None,
        "rpd": None,
        "tpm": None,
        "tpd": None,
    }
    values.update(overrides)
    return ProviderConfig(**values)


def env_for(*providers: ProviderConfig) -> dict[str, str]:
    return {p.api_key_env: f"key-{p.id}" for p in providers if p.api_key_env}


def ok(text: str | None = "Hồ sơ đang gọi vốn.", *, tool_calls: list[dict] | None = None,
       usage: tuple[int, int] = (120, 30), model: str = "model-that", finish: str = "stop") -> httpx.Response:
    message: dict[str, Any] = {"role": "assistant", "content": text}
    if tool_calls is not None:
        message["tool_calls"] = tool_calls
    return httpx.Response(200, json={
        "model": model,
        "choices": [{"index": 0, "message": message, "finish_reason": finish}],
        "usage": {"prompt_tokens": usage[0], "completion_tokens": usage[1]},
    })


def loi(status: int, body: Any = None, headers: dict[str, str] | None = None) -> httpx.Response:
    return httpx.Response(status, json=body if body is not None else {"error": {"code": f"e{status}"}},
                          headers=headers)


TIMEOUT = "TIMEOUT"


@dataclass
class FakeLlm:
    """Kịch bản theo host: mỗi lần gọi lấy phần tử kế tiếp (phần tử cuối dùng lại mãi)."""

    scripts: dict[str, list[Any]]
    calls: list[tuple[str, dict[str, Any], dict[str, str]]] = field(default_factory=list)

    async def handler(self, request: httpx.Request) -> httpx.Response:
        host = request.url.host.removesuffix(".test")
        self.calls.append((host, json.loads(request.content), dict(request.headers)))
        script = self.scripts[host]
        item = script.pop(0) if len(script) > 1 else script[0]
        if item == TIMEOUT:
            raise httpx.ReadTimeout("hết giờ", request=request)
        if callable(item):
            return await item(request)
        # Trả bản sao: một Response dùng lại nhiều lần thì body chỉ đọc được lần đầu.
        return httpx.Response(item.status_code, headers=item.headers, content=item.content)

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)

    def hosts(self) -> list[str]:
        return [host for host, _, _ in self.calls]


def make_router(fake: FakeLlm, providers: list[ProviderConfig], *, public_extra: list[ProviderConfig] = (),
                clock: FakeClock | None = None, **kwargs: Any) -> LlmRouter:
    return LlmRouter(
        providers,
        public_extra=list(public_extra),
        transport=fake.transport(),
        clock=clock or FakeClock(),
        env=env_for(*providers, *public_extra),
        **kwargs,
    )


class TestChonNhaTheoLan:
    @pytest.mark.asyncio
    async def test_public_thu_nha_public_extra_truoc(self):
        a, gemini = nha("a"), nha("gemini", lanes=CHI_PUBLIC)
        fake = FakeLlm({"a": [ok("từ a")], "gemini": [ok("từ gemini")]})
        result = await make_router(fake, [a], public_extra=[gemini]).complete(CAU_HOI, lane=Lane.PUBLIC)
        assert result.provider == "gemini"
        assert fake.hosts() == ["gemini"]

    @pytest.mark.asyncio
    async def test_user_data_khong_bao_gio_toi_nha_chi_public(self):
        # Kể cả khi cấu hình nhầm nhà chỉ-PUBLIC vào danh sách chính.
        gemini, a = nha("gemini", lanes=CHI_PUBLIC), nha("a")
        fake = FakeLlm({"a": [ok("từ a")], "gemini": [ok("từ gemini")]})
        router = make_router(fake, [gemini, a], public_extra=[gemini])
        result = await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "a"
        assert fake.hosts() == ["a"]
        assert router.provider_ids(Lane.USER_DATA) == ["a"]
        assert router.provider_ids(Lane.PUBLIC) == ["gemini", "a"]

    @pytest.mark.asyncio
    async def test_khong_co_nha_nao_cho_lan(self):
        fake = FakeLlm({"gemini": [ok()]})
        router = make_router(fake, [], public_extra=[nha("gemini", lanes=CHI_PUBLIC)])
        with pytest.raises(LlmUnavailable) as err:
            await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert err.value.attempts == (("*", "KHONG_CO_NHA"),)
        assert fake.calls == []


class TestChuyenNha:
    @pytest.mark.asyncio
    @pytest.mark.parametrize("that_bai", [
        loi(429, headers={"retry-after": "5"}),
        loi(402),
        loi(503),
        loi(408),
        TIMEOUT,
        httpx.Response(200, json={"error": {"code": "provider_error", "message": "upstream"}}),
        ok("dở dang", finish="error"),
        httpx.Response(200, text="<html>không phải json</html>"),
    ], ids=["429", "402", "503", "408", "timeout", "error-trong-body", "finish-error", "json-hong"])
    async def test_loi_tam_thoi_chuyen_sang_nha_ke_tiep(self, that_bai):
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [that_bai], "b": [ok("từ b")]})
        result = await make_router(fake, [a, b]).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "b"
        assert result.text == "từ b"
        assert fake.hosts() == ["a", "b"]

    @pytest.mark.asyncio
    async def test_loi_cau_hinh_chuyen_nha_va_ghi_warning_khong_lo_prompt(self, caplog):
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(401, {"error": {"code": "invalid_api_key", "message": "Hồ sơ LA-2026-000123"}})],
                        "b": [ok()]})
        with caplog.at_level(logging.INFO, logger="app.services.assistant.llm.router"):
            result = await make_router(fake, [a, b]).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "b"
        warnings = [r for r in caplog.records if r.levelno == logging.WARNING]
        assert len(warnings) == 1
        assert "nha=a" in warnings[0].getMessage()
        assert "status=401" in warnings[0].getMessage()
        assert "invalid_api_key" in warnings[0].getMessage()
        # Không log câu hỏi, câu trả lời, thông điệp lỗi tự do hay key.
        assert "LA-2026-000123" not in caplog.text
        assert "Hồ sơ đang gọi vốn" not in caplog.text
        assert "key-a" not in caplog.text

    @pytest.mark.asyncio
    async def test_het_nha_thi_llm_unavailable_kem_ly_do(self):
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(500)], "b": [TIMEOUT]})
        with pytest.raises(LlmUnavailable) as err:
            await make_router(fake, [a, b]).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert err.value.attempts == (("a", "LOI_TAM_THOI"), ("b", "TIMEOUT"))

    @pytest.mark.asyncio
    async def test_429_theo_phut_nghi_theo_retry_after(self):
        clock = FakeClock()
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(429, headers={"retry-after": "20"}), ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert fake.hosts() == ["a", "b", "b"]  # lần 2 không gọi a vì còn trong 20 giây nghỉ
        clock.advance(21)
        result = await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "a"

    @pytest.mark.asyncio
    async def test_429_het_han_muc_ngay_khoa_toi_ngay_moi(self):
        clock = FakeClock()
        a, b = nha("a"), nha("b")
        body = {"error": {"code": "rate_limit_exceeded",
                          "message": "Rate limit reached on requests per day (RPD): Limit 1000"}}
        fake = FakeLlm({"a": [loi(429, body, {"retry-after": "2"}), ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        clock.advance(3600)  # retry-after đã qua lâu nhưng vẫn cùng ngày UTC
        await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert fake.hosts() == ["a", "b", "b"]
        clock.advance(16 * 3600 + 1)  # sang 01:00 UTC ngày 16
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "a"


class TestNgatTam:
    @pytest.mark.asyncio
    async def test_ba_loi_lien_tiep_thi_bo_qua_60_giay_roi_thu_lai(self):
        clock = FakeClock()
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(503), loi(503), loi(503), ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        for _ in range(3):
            await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert router.breaker("a").state == "open"
        await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert fake.hosts() == ["a", "b", "a", "b", "a", "b", "b"]  # lần 4 bỏ qua a
        clock.advance(60)
        result = await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "a"
        assert router.breaker("a").state == "closed"


class TestHanMuc:
    @pytest.mark.asyncio
    async def test_het_rpd_thi_bo_qua_nha_va_mo_lai_hom_sau(self):
        clock = FakeClock()
        a, b = nha("a", rpd=1), nha("b")
        fake = FakeLlm({"a": [ok("từ a")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "a"
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "b"
        clock.advance(24 * 3600)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "a"

    @pytest.mark.asyncio
    async def test_het_tpd_thi_khong_gui_request(self):
        a = nha("a", tpd=500)
        fake = FakeLlm({"a": [ok()]})
        with pytest.raises(LlmUnavailable) as err:
            await make_router(fake, [a]).complete(CAU_HOI, lane=Lane.USER_DATA, max_tokens=800)
        assert err.value.attempts == (("a", "HET_HAN_MUC"),)
        assert fake.calls == []

    @pytest.mark.asyncio
    async def test_so_tay_ghi_token_that_sau_khi_tra_loi(self):
        a = nha("a", tpd=200000)
        fake = FakeLlm({"a": [ok(usage=(150, 40))]})
        router = make_router(fake, [a])
        await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert router.budget("a").day_tokens == 190
        assert router.budget("a").day_requests == 1


class TestParse:
    @pytest.mark.asyncio
    async def test_parse_tool_calls_tu_chuoi_json(self):
        a = nha("a")
        tool_calls = [{"id": "call_1", "type": "function",
                       "function": {"name": "chi_tiet_ho_so", "arguments": "{\"applicationNumber\": \"LA-2026-000123\"}"}}]
        fake = FakeLlm({"a": [ok(None, tool_calls=tool_calls, finish="tool_calls", usage=(300, 25))]})
        result = await make_router(fake, [a]).complete(CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS)
        assert result.text is None
        assert result.tool_calls == [ToolCall(id="call_1", name="chi_tiet_ho_so",
                                              arguments={"applicationNumber": "LA-2026-000123"})]
        assert (result.prompt_tokens, result.completion_tokens) == (300, 25)
        assert result.model == "model-that"

    @pytest.mark.asyncio
    async def test_arguments_dang_object_va_rong_van_nhan(self):
        a = nha("a")
        tool_calls = [
            {"id": "c1", "function": {"name": "ho_so_cua_toi", "arguments": ""}},
            {"function": {"name": "chi_tiet_ho_so", "arguments": {"applicationNumber": "LA-2026-000123"}}},
        ]
        fake = FakeLlm({"a": [ok("", tool_calls=tool_calls)]})
        result = await make_router(fake, [a]).complete(CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS)
        assert result.tool_calls[0].arguments == {}
        assert result.tool_calls[1].id == "call_1"
        assert result.tool_calls[1].arguments == {"applicationNumber": "LA-2026-000123"}

    @pytest.mark.asyncio
    async def test_arguments_json_hong_coi_nhu_loi_nha_do(self):
        a, b = nha("a"), nha("b")
        broken = [{"id": "c1", "function": {"name": "chi_tiet_ho_so", "arguments": "{\"applicationNumber\": "}}]
        fake = FakeLlm({"a": [ok(None, tool_calls=broken)], "b": [ok("từ b")]})
        router = make_router(fake, [a, b])
        result = await router.complete(CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS)
        assert result.provider == "b"
        with pytest.raises(LlmUnavailable) as err:
            await make_router(FakeLlm({"a": [ok(None, tool_calls=broken)]}), [a]).complete(
                CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS)
        assert err.value.attempts == (("a", "THAM_SO_TOOL_HONG"),)

    @pytest.mark.asyncio
    async def test_bo_khoi_suy_luan_va_tra_loi_rong_la_loi(self):
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [ok("<think>nghĩ thầm</think>   ")], "b": [ok("<think>x</think>Đã duyệt.")]})
        result = await make_router(fake, [a, b]).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "b"
        assert result.text == "Đã duyệt."

    @pytest.mark.asyncio
    async def test_thieu_usage_thi_uoc_luong(self):
        a = nha("a")
        response = httpx.Response(200, json={"choices": [{"message": {"content": "Chào bạn"}, "finish_reason": "stop"}]})
        result = await make_router(FakeLlm({"a": [response]}), [a]).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.prompt_tokens == estimate_tokens(CAU_HOI)
        assert result.completion_tokens >= 1
        assert result.model == "model-a"


class TestRequestGuiDi:
    @pytest.mark.asyncio
    async def test_body_va_header_dung_chuan_openai(self):
        a = nha("a", extra_body={"reasoning_effort": "none"}, extra_headers={"X-Title": "FINORA"}, sends_user=True)
        fake = FakeLlm({"a": [ok()]})
        await make_router(fake, [a]).complete(CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS, max_tokens=500,
                                              user_hash="hash-u1")
        (_, body, headers), = fake.calls
        assert headers["authorization"] == "Bearer key-a"
        assert headers["x-title"] == "FINORA"
        assert body["model"] == "model-a"
        assert body["messages"] == CAU_HOI
        assert body["temperature"] == 0.2
        assert body["max_tokens"] == 500
        assert body["tools"] == TOOLS
        assert body["reasoning_effort"] == "none"
        assert body["user"] == "hash-u1"

    @pytest.mark.asyncio
    async def test_khong_gui_user_va_tool_khi_nha_khong_ho_tro(self):
        a = nha("a")  # sends_user mặc định False
        fake = FakeLlm({"a": [ok()]})
        await make_router(fake, [a]).complete(CAU_HOI, lane=Lane.USER_DATA, user_hash="hash-u1")
        (_, body, _), = fake.calls
        assert "user" not in body
        assert "tools" not in body

    @pytest.mark.asyncio
    async def test_bo_qua_nha_khong_ho_tro_tool_khi_luot_can_tool(self):
        a, b = nha("a", supports_tools=False), nha("b")
        fake = FakeLlm({"a": [ok("từ a")], "b": [ok("từ b")]})
        result = await make_router(fake, [a, b]).complete(CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS)
        assert result.provider == "b"
        assert fake.hosts() == ["b"]

    @pytest.mark.asyncio
    async def test_nha_khong_can_key_thi_khong_gui_authorization(self):
        local = nha("local", api_key_env="")
        fake = FakeLlm({"local": [ok()]})
        await make_router(fake, [local]).complete(CAU_HOI, lane=Lane.USER_DATA)
        (_, _, headers), = fake.calls
        assert "authorization" not in headers


class TestGhimNha:
    @pytest.mark.asyncio
    async def test_pin_provider_chi_goi_dung_nha_do_va_khong_chuyen_nha(self):
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [ok("từ a")], "b": [loi(503)]})
        router = make_router(fake, [a, b])
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA, pin_provider="a")).provider == "a"
        with pytest.raises(LlmUnavailable) as err:
            await router.complete(CAU_HOI, lane=Lane.USER_DATA, pin_provider="b")
        assert err.value.attempts == (("b", "LOI_TAM_THOI"),)
        assert fake.hosts() == ["a", "b"]

    @pytest.mark.asyncio
    async def test_pin_khong_vuot_luat_lan(self):
        gemini = nha("gemini", lanes=CHI_PUBLIC)
        fake = FakeLlm({"gemini": [ok()]})
        router = make_router(fake, [], public_extra=[gemini])
        with pytest.raises(LlmUnavailable):
            await router.complete(CAU_HOI, lane=Lane.USER_DATA, pin_provider="gemini")
        assert fake.calls == []


class TestSemaphore:
    @pytest.mark.asyncio
    async def test_het_cho_qua_wait_slot_thi_llm_unavailable(self):
        release = asyncio.Event()

        async def cham(request: httpx.Request) -> httpx.Response:
            await release.wait()
            return ok("xong")

        a = nha("a")
        fake = FakeLlm({"a": [cham]})
        router = make_router(fake, [a], max_concurrent=1, wait_slot_s=0.05)
        first = asyncio.create_task(router.complete(CAU_HOI, lane=Lane.USER_DATA))
        await asyncio.sleep(0)  # để lượt đầu chiếm chỗ
        with pytest.raises(LlmUnavailable) as err:
            await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert err.value.attempts == (("*", "HET_CHO"),)
        release.set()
        assert (await first).text == "xong"
        # Chỗ đã được trả lại: lượt sau chạy bình thường.
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).text == "xong"


def test_get_llm_router_dung_settings_va_env(monkeypatch):
    settings = make_settings(
        provider_order=("groq_gptoss120b", "openrouter_gemma31b", "gemini_gemma31b"),
        public_extra_providers=("gemini_gemma31b",),
        llm_timeout_s=12.0,
        max_concurrent_llm=3,
    )
    monkeypatch.setattr(router_module, "get_settings", lambda: settings)
    monkeypatch.setenv("GROQ_API_KEY", "gsk-test")
    monkeypatch.setenv("GEMINI_API_KEY", "gm-test")
    monkeypatch.delenv("OPENROUTER_API_KEY", raising=False)
    get_llm_router.cache_clear()
    try:
        router = get_llm_router()
        # OpenRouter thiếu key bị bỏ; Gemini dù nằm trong danh sách chính vẫn không vào làn USER_DATA.
        assert router.provider_ids(Lane.USER_DATA) == ["groq_gptoss120b"]
        assert router.provider_ids(Lane.PUBLIC) == ["gemini_gemma31b", "groq_gptoss120b"]
        assert get_llm_router() is router
    finally:
        get_llm_router.cache_clear()


# ── bổ sung ngoài brief: các lỗi tìm thấy khi rà code ─────────────────────────


class TestBoSung:
    @pytest.mark.asyncio
    async def test_huy_luot_dang_thu_half_open_khong_khoa_nha_mai_mai(self):
        # Orchestrator hủy lượt khi quá 40 giây (asyncio.wait_for). Nếu request bị hủy đúng lúc
        # đang là request thử của cầu dao, cờ "đang thử" phải được trả lại — nếu không nhà
        # này bị bỏ qua tới khi restart. Chỗ semaphore cũng phải được trả lại.
        clock = FakeClock()
        started = asyncio.Event()

        async def treo(request: httpx.Request) -> httpx.Response:
            started.set()
            await asyncio.Event().wait()  # không bao giờ trả lời
            return ok()

        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(503), loi(503), loi(503), treo, ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock, max_concurrent=1, wait_slot_s=0.05)
        for _ in range(3):
            await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert router.breaker("a").state == "open"
        clock.advance(60)
        task = asyncio.create_task(router.complete(CAU_HOI, lane=Lane.USER_DATA))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert router.breaker("a").state == "half_open"
        result = await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "a"
        assert router.breaker("a").state == "closed"

    @pytest.mark.asyncio
    async def test_429_theo_phut_co_so_3036_trong_thong_diep_khong_khoa_ca_ngay(self):
        # Thông điệp 429 theo phút của Groq có số token "Requested 3036": không được nhầm với
        # mã 3036 "hết neuron ngày" của Cloudflare mà khóa nhà cả ngày.
        clock = FakeClock()
        body = {"error": {
            "message": "Rate limit reached for model `openai/gpt-oss-120b` in organization `org_01x` "
                       "service tier `on_demand` on tokens per minute (TPM): Limit 8000, Used 6000, "
                       "Requested 3036. Please try again in 15s.",
            "type": "tokens", "code": "rate_limit_exceeded"}}
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(429, body, {"retry-after": "15"}), ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "b"
        clock.advance(16)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "a"

    @pytest.mark.asyncio
    async def test_cloudflare_ma_3036_khoa_toi_00h_utc(self):
        clock = FakeClock()
        body = {"errors": [{"code": 3036, "message": "Account limited"}], "success": False}
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(429, body), ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "b"
        clock.advance(3600)  # quá 60 giây nghỉ mặc định nhưng vẫn cùng ngày UTC
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "b"
        clock.advance(16 * 3600)  # 00:00 UTC ngày 16
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "a"

    @pytest.mark.asyncio
    async def test_retry_after_khong_hop_le_thi_nghi_mac_dinh(self):
        clock = FakeClock()
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [loi(429, headers={"retry-after": "nan"}), ok("a đã hồi")], "b": [ok("từ b")]})
        router = make_router(fake, [a, b], clock=clock)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "b"
        clock.advance(30)
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "b"
        clock.advance(31)  # quá DEFAULT_RETRY_AFTER_S = 60
        assert (await router.complete(CAU_HOI, lane=Lane.USER_DATA)).provider == "a"

    @pytest.mark.asyncio
    async def test_khoi_suy_luan_chua_dong_khong_lot_ra_nguoi_dung(self):
        # Hết max_tokens giữa khối suy luận: "<think>" không có thẻ đóng.
        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [ok("<think>đang cân nhắc hồ sơ", finish="length")], "b": [ok("từ b")]})
        result = await make_router(fake, [a, b]).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "b"
        assert result.text == "từ b"

    @pytest.mark.asyncio
    async def test_id_tool_call_tu_sinh_khong_trung_id_co_san(self):
        # Task 14 ghép kết quả tool theo tool_call_id: hai lời gọi trùng id sẽ bị nhầm kết quả.
        a = nha("a")
        tool_calls = [
            {"id": "call_1", "function": {"name": "ho_so_cua_toi", "arguments": "{}"}},
            {"function": {"name": "chi_tiet_ho_so", "arguments": {"applicationNumber": "LA-2026-000123"}}},
        ]
        fake = FakeLlm({"a": [ok(None, tool_calls=tool_calls)]})
        result = await make_router(fake, [a]).complete(CAU_HOI, lane=Lane.USER_DATA, tools=TOOLS)
        ids = [call.id for call in result.tool_calls]
        assert ids[0] == "call_1"
        assert len(set(ids)) == 2

    @pytest.mark.asyncio
    async def test_qua_timeout_tong_cua_mot_request_thi_chuyen_nha(self):
        # httpx chỉ giới hạn từng bước (connect/read/...), không giới hạn tổng thời gian; spec 7.4
        # yêu cầu 20 giây MỖI request nên router tự đặt hạn tổng.
        async def cham(request: httpx.Request) -> httpx.Response:
            await asyncio.sleep(1)
            return ok("muộn")

        a, b = nha("a"), nha("b")
        fake = FakeLlm({"a": [cham], "b": [ok("từ b")]})
        result = await make_router(fake, [a, b], timeout_s=0.05).complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "b"

    @pytest.mark.asyncio
    async def test_base_url_hong_la_loi_cau_hinh_va_chuyen_nha(self):
        bad, b = nha("bad", base_url="http://local\nhost/v1"), nha("b")
        fake = FakeLlm({"b": [ok("từ b")]})
        router = make_router(fake, [bad, b])
        result = await router.complete(CAU_HOI, lane=Lane.USER_DATA)
        assert result.provider == "b"
        with pytest.raises(LlmUnavailable) as err:
            await router.complete(CAU_HOI, lane=Lane.USER_DATA, pin_provider="bad")
        assert err.value.attempts == (("bad", "CAU_HINH_SAI"),)

    @pytest.mark.asyncio
    async def test_gemini_402_boc_trong_mang_van_lay_duoc_ma_loi_va_khoa_ca_ngay(self, caplog):
        # Endpoint tương thích OpenAI của Gemini trả lỗi dạng MẢNG [{"error": {...}}] (đo thật
        # 2026-10-16 với key hết credit). Mã lỗi vẫn phải lên log; thông điệp thì không.
        clock = FakeClock()
        body = [{"error": {"code": 402, "message": "Your prepayment credits are depleted. LA-2026-000123",
                           "status": "RESOURCE_EXHAUSTED"}}]
        gemini, a = nha("gemini", lanes=CHI_PUBLIC), nha("a")
        fake = FakeLlm({"gemini": [loi(402, body), ok("gemini hồi")], "a": [ok("từ a")]})
        router = make_router(fake, [a], public_extra=[gemini], clock=clock)
        with caplog.at_level(logging.INFO, logger="app.services.assistant.llm.router"):
            assert (await router.complete(CAU_HOI, lane=Lane.PUBLIC)).provider == "a"
        assert "ly_do=CAN_THANH_TOAN status=402 ma=402" in caplog.text
        assert "prepayment" not in caplog.text
        assert "LA-2026-000123" not in caplog.text
        clock.advance(3600)
        assert (await router.complete(CAU_HOI, lane=Lane.PUBLIC)).provider == "a"
        assert fake.hosts() == ["gemini", "a", "a"]

    @pytest.mark.asyncio
    async def test_gemini_429_het_ngay_boc_trong_mang(self):
        clock = FakeClock()
        body = [{"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "Quota exceeded",
                           "details": [{"violations": [
                               {"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}]
        gemini, a = nha("gemini", lanes=CHI_PUBLIC, reset_tz="America/Los_Angeles"), nha("a")
        fake = FakeLlm({"gemini": [loi(429, body, {"retry-after": "3"}), ok("gemini hồi")], "a": [ok("từ a")]})
        router = make_router(fake, [a], public_extra=[gemini], clock=clock)
        assert (await router.complete(CAU_HOI, lane=Lane.PUBLIC)).provider == "a"
        clock.advance(3600)  # 09:00 UTC = 02:00 giờ Los Angeles, vẫn cùng ngày ở đó
        assert (await router.complete(CAU_HOI, lane=Lane.PUBLIC)).provider == "a"
        clock.advance(22 * 3600)  # 07:00 UTC ngày 16 = 00:00 giờ Los Angeles ngày 16
        assert (await router.complete(CAU_HOI, lane=Lane.PUBLIC)).provider == "gemini"

    @pytest.mark.asyncio
    async def test_public_extra_thieu_lan_public_khong_duoc_dung(self):
        # Làn quyết định bởi cấu hình nhà, không bởi vị trí trong danh sách.
        only_user = nha("u", lanes=frozenset({Lane.USER_DATA}))
        a = nha("a")
        fake = FakeLlm({"u": [ok("từ u")], "a": [ok("từ a")]})
        router = make_router(fake, [a], public_extra=[only_user])
        assert router.provider_ids(Lane.PUBLIC) == ["a"]
        assert (await router.complete(CAU_HOI, lane=Lane.PUBLIC)).provider == "a"
