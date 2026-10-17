"""Router gọi LLM của Trợ lý FINORA: chọn nhà theo làn dữ liệu, chuyển nhà khi lỗi (spec 7.2–7.4).

Một lần ``complete()`` = tối đa một request thành công tới MỘT nhà. Thứ tự thử:

* làn ``PUBLIC``: các nhà ``public_extra`` (Gemini) trước, rồi danh sách chính;
* làn ``USER_DATA``: chỉ danh sách chính, và loại mọi nhà không khai ``Lane.USER_DATA`` —
  kể cả khi cấu hình nhầm Gemini vào ``ASSISTANT_PROVIDERS``.

Mỗi nhà phải qua ba cửa trước khi được gọi: còn hạn mức (``ProviderBudget``), không bị ngắt
tạm (``CircuitBreaker``), hỗ trợ tool nếu lượt cần tool. Lỗi tạm thời (429/402/408/5xx/timeout,
``finish_reason="error"``, body có ``error``, JSON hỏng) → thử nhà kế tiếp. Lỗi cấu hình
(400/401/403/404...) cũng chuyển nhà nhưng ghi WARNING để người vận hành sửa key/model.
Hết nhà → ``LlmUnavailable`` → orchestrator trả câu mẫu (``mode=fallback``).

Không bao giờ log nội dung prompt, câu trả lời hay body lỗi (body có thể lặp lại prompt):
chỉ id nhà, model, mã trạng thái, lý do, token, độ trễ.
"""

from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import re
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from functools import lru_cache
from typing import Any

import httpx

from app.services.assistant.llm.providers import Lane, ProviderConfig, build_providers
from app.services.assistant.llm.quota import CircuitBreaker, ProviderBudget
from app.services.assistant.settings import get_settings

logger = logging.getLogger(__name__)

#: Chờ tối đa bao lâu để có chỗ gọi LLM (semaphore); quá thì orchestrator trả câu mẫu.
WAIT_SLOT_S = 10.0
#: 429 không kèm retry-after thì nghỉ nhà đó chừng này giây.
DEFAULT_RETRY_AFTER_S = 60.0
#: Nhiệt độ thấp: trợ lý giải thích số liệu, cần ổn định hơn là sáng tạo.
TEMPERATURE = 0.2

# Dấu hiệu 429 là "hết hạn mức NGÀY" chứ không phải "gọi nhanh quá trong phút": Groq ghi
# "per day (RPD/TPD)", OpenRouter "free-models-per-day", Gemini "...PerDay...", Cloudflare
# "daily free allocation". Gặp thì khóa nhà tới mốc reset thay vì thử lại mỗi phút.
# RPD/TPD phải đứng riêng một từ: chuỗi ngẫu nhiên như id tổ chức "org_..." không được khớp.
_DAU_HIEU_HET_NGAY = re.compile(r"per[\s_-]?day|daily|\brpd\b|\btpd\b", re.IGNORECASE)
# Mã 3036 của Cloudflare (hết 10.000 neuron/ngày) chỉ so ở TRƯỜNG MÃ LỖI, không tìm trong văn bản:
# thông điệp 429 theo phút của Groq có số token kiểu "Requested 3036" và sẽ bị khóa oan cả ngày.
_MA_HET_NGAY = frozenset({"3036"})
# Khối suy luận lọt vào content; thiếu thẻ đóng (hết max_tokens giữa chừng) thì bỏ tới hết chuỗi.
_THE_SUY_LUAN = re.compile(r"<think>.*?(?:</think>|\Z)", re.DOTALL | re.IGNORECASE)
_MA_LOI_AN_TOAN = re.compile(r"^[A-Za-z0-9_.\-]{1,60}$")


@dataclass(frozen=True)
class ToolCall:
    """Một lời gọi công cụ do model đề nghị; ``arguments`` đã parse từ chuỗi JSON."""

    id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class LlmResult:
    text: str | None
    tool_calls: list[ToolCall]
    provider: str
    model: str
    prompt_tokens: int
    completion_tokens: int


class LlmUnavailable(Exception):
    """Không nhà nào trả lời được. ``attempts`` = [(id nhà, mã lý do)] để ghi log/audit."""

    def __init__(self, message: str, attempts: Sequence[tuple[str, str]] = ()) -> None:
        super().__init__(message)
        self.attempts: tuple[tuple[str, str], ...] = tuple(attempts)


class _ProviderFailure(Exception):
    """Lỗi của một nhà trong một lần gọi — nội bộ router, không lọt ra ngoài.

    ``reason`` là mã ngắn để log/audit; ``detail`` là mã lỗi của nhà (error.code), không bao
    giờ là thông điệp tự do vì thông điệp lỗi có thể lặp lại nội dung prompt.
    """

    def __init__(self, reason: str, status: int | None = None, *, config_error: bool = False, detail: str = "") -> None:
        super().__init__(reason)
        self.reason = reason
        self.status = status
        self.config_error = config_error
        self.detail = detail


def estimate_tokens(messages: Sequence[Mapping[str, Any]], tools: Sequence[Mapping[str, Any]] | None = None) -> int:
    """Ước lượng thô số token đầu vào: số ký tự JSON chia 3 (spec 7.3).

    Chỉ để sổ tay hạn mức quyết định trước khi gửi; sau khi nhà trả ``usage`` thì sổ được sửa
    lại bằng số thật (``ProviderBudget.adjust_tokens``).
    """
    chars = len(json.dumps(list(messages), ensure_ascii=False))
    if tools:
        chars += len(json.dumps(list(tools), ensure_ascii=False))
    return max(1, chars // 3)


def _mo_goi_loi(payload: Any) -> Any:
    """Body lỗi của Gemini (endpoint tương thích OpenAI) là MẢNG ``[{"error": {...}}]``: lấy phần tử đầu."""
    if isinstance(payload, list) and payload and isinstance(payload[0], dict):
        return payload[0]
    return payload


def _ma_loi_ngan(payload: Any) -> str:
    """Lấy mã lỗi ngắn (error.code/type) để log; bỏ qua mọi thứ có thể là văn bản tự do."""
    payload = _mo_goi_loi(payload)
    if not isinstance(payload, dict):
        return ""
    error = payload.get("error")
    candidates: list[Any] = []
    if isinstance(error, dict):
        candidates = [error.get("code"), error.get("type"), error.get("status")]
    elif isinstance(payload.get("errors"), list) and payload["errors"]:
        first = payload["errors"][0]
        if isinstance(first, dict):
            candidates = [first.get("code")]
    for value in candidates:
        text = str(value) if value is not None else ""
        if _MA_LOI_AN_TOAN.match(text):
            return text
    return ""


def _cac_ma_loi(payload: Any) -> set[str]:
    """Mọi mã lỗi trong body (``error.code`` kiểu OpenAI, ``errors[].code`` kiểu Cloudflare)."""
    payload = _mo_goi_loi(payload)
    if not isinstance(payload, dict):
        return set()
    items: list[Any] = []
    if isinstance(payload.get("error"), dict):
        items.append(payload["error"])
    if isinstance(payload.get("errors"), list):
        items.extend(payload["errors"])
    return {str(item["code"]) for item in items if isinstance(item, dict) and item.get("code") is not None}


def _het_han_muc_ngay(payload: Any, body_text: str) -> bool:
    return bool(_DAU_HIEU_HET_NGAY.search(body_text)) or not _MA_HET_NGAY.isdisjoint(_cac_ma_loi(payload))


def _retry_after(response: httpx.Response) -> float:
    raw = response.headers.get("retry-after", "")
    try:
        seconds = float(raw)
    except ValueError:
        return DEFAULT_RETRY_AFTER_S
    if not math.isfinite(seconds):
        # "nan" qua được float() nhưng làm block_for thành 0 giây — coi như không có header.
        return DEFAULT_RETRY_AFTER_S
    return min(max(seconds, 0.0), 3600.0)


def _parse_arguments(raw: Any) -> dict[str, Any]:
    """Tham số tool theo chuẩn OpenAI là CHUỖI JSON; vài nhà (Ollama) trả sẵn object."""
    if raw is None or raw == "":
        return {}
    if isinstance(raw, dict):
        return raw
    if not isinstance(raw, str):
        raise _ProviderFailure("THAM_SO_TOOL_HONG")
    try:
        parsed = json.loads(raw)
    except ValueError as exc:
        raise _ProviderFailure("THAM_SO_TOOL_HONG") from exc
    if not isinstance(parsed, dict):
        raise _ProviderFailure("THAM_SO_TOOL_HONG")
    return parsed


def _parse_tool_calls(raw: Any) -> list[ToolCall]:
    if not raw:
        return []
    if not isinstance(raw, list):
        raise _ProviderFailure("THAM_SO_TOOL_HONG")
    calls: list[ToolCall] = []
    used_ids: set[str] = set()
    for index, item in enumerate(raw):
        function = item.get("function") if isinstance(item, dict) else None
        if not isinstance(function, dict) or not isinstance(function.get("name"), str) or not function["name"]:
            raise _ProviderFailure("THAM_SO_TOOL_HONG")
        raw_id = item.get("id")
        call_id = raw_id if isinstance(raw_id, str) and raw_id else f"call_{index}"
        # Orchestrator ghép kết quả tool theo tool_call_id: id tự sinh (hoặc id nhà trả trùng)
        # mà trùng nhau thì hai kết quả bị nhầm chỗ, nên thêm hậu tố tới khi duy nhất.
        while call_id in used_ids:
            call_id = f"{call_id}_{index}"
        used_ids.add(call_id)
        calls.append(ToolCall(id=call_id, name=function["name"], arguments=_parse_arguments(function.get("arguments"))))
    return calls


def _clean_text(content: Any) -> str | None:
    if not isinstance(content, str):
        return None
    # Phòng model lỡ trả khối suy luận trong content (qwen khi chưa tắt hẳn suy luận).
    cleaned = _THE_SUY_LUAN.sub("", content).strip()
    return cleaned or None


class LlmRouter:
    def __init__(
        self,
        providers: Sequence[ProviderConfig],
        *,
        public_extra: Sequence[ProviderConfig] = (),
        timeout_s: float = 20.0,
        max_concurrent: int = 2,
        wait_slot_s: float = WAIT_SLOT_S,
        transport: httpx.AsyncBaseTransport | None = None,
        clock: Callable[[], float] = time.time,
        env: Mapping[str, str] | None = None,
    ) -> None:
        self._providers = list(providers)
        self._public_extra = list(public_extra)
        self._timeout_s = timeout_s
        self._wait_slot_s = wait_slot_s
        self._transport = transport
        self._env: Mapping[str, str] = os.environ if env is None else env
        self._semaphore = asyncio.Semaphore(max_concurrent)
        self._client: httpx.AsyncClient | None = None
        self._budgets: dict[str, ProviderBudget] = {}
        self._breakers: dict[str, CircuitBreaker] = {}
        for provider in [*self._public_extra, *self._providers]:
            self._budgets.setdefault(provider.id, ProviderBudget(provider, clock))
            self._breakers.setdefault(provider.id, CircuitBreaker(clock=clock))

    # ── chọn nhà ────────────────────────────────────────────────────────────────

    def candidates(self, lane: Lane, pin_provider: str | None = None) -> list[ProviderConfig]:
        """Danh sách nhà theo thứ tự thử cho một làn. Ghim nhà không bao giờ vượt luật làn."""
        pool = [*self._public_extra, *self._providers] if lane == Lane.PUBLIC else list(self._providers)
        seen: set[str] = set()
        result: list[ProviderConfig] = []
        for provider in pool:
            if provider.id in seen or lane not in provider.lanes:
                continue
            seen.add(provider.id)
            result.append(provider)
        if pin_provider is not None:
            result = [provider for provider in result if provider.id == pin_provider]
        return result

    def provider_ids(self, lane: Lane) -> list[str]:
        return [provider.id for provider in self.candidates(lane)]

    def budget(self, provider_id: str) -> ProviderBudget:
        return self._budgets[provider_id]

    def breaker(self, provider_id: str) -> CircuitBreaker:
        return self._breakers[provider_id]

    # ── gọi ─────────────────────────────────────────────────────────────────────

    async def complete(
        self,
        messages: list[dict[str, Any]],
        *,
        lane: Lane,
        tools: list[dict[str, Any]] | None = None,
        max_tokens: int = 800,
        pin_provider: str | None = None,
        user_hash: str | None = None,
    ) -> LlmResult:
        """Gửi ``messages`` tới nhà đầu tiên còn dùng được trong làn ``lane``.

        ``pin_provider`` ghim đúng một nhà và tắt chuyển nhà (dùng khi đánh giá từng model).
        ``user_hash`` chỉ gửi cho nhà khai ``sends_user`` (OpenRouter).
        """
        candidates = self.candidates(lane, pin_provider)
        if not candidates:
            raise LlmUnavailable(f"Không có nhà cung cấp LLM cho làn {lane.value}", [("*", "KHONG_CO_NHA")])
        estimated_prompt = estimate_tokens(messages, tools)
        estimated_total = estimated_prompt + max_tokens

        try:
            await asyncio.wait_for(self._semaphore.acquire(), timeout=self._wait_slot_s)
        except TimeoutError as exc:
            logger.warning("Trợ lý: hết chỗ gọi LLM sau %.1f giây", self._wait_slot_s)
            raise LlmUnavailable("Hết chỗ gọi LLM", [("*", "HET_CHO")]) from exc

        attempts: list[tuple[str, str]] = []
        try:
            for provider in candidates:
                if tools and not provider.supports_tools:
                    attempts.append((provider.id, "KHONG_HO_TRO_TOOL"))
                    continue
                budget = self._budgets[provider.id]
                breaker = self._breakers[provider.id]
                if not budget.can_spend(estimated_total):
                    attempts.append((provider.id, "HET_HAN_MUC"))
                    continue
                if not breaker.allow():
                    attempts.append((provider.id, "NGAT_TAM"))
                    continue
                # Ngay sau allow(): "half_open" nghĩa là request này đang giữ lượt thử của cầu dao.
                holds_trial = breaker.state == "half_open"
                # Giữ chỗ trong sổ TRƯỚC khi await, để request song song thấy đúng số đã dùng.
                reserved_at = budget.record(estimated_total, 0)
                started = time.perf_counter()
                try:
                    result = await self._call(provider, messages, tools, max_tokens, user_hash)
                except _ProviderFailure as failure:
                    budget.adjust_tokens(-estimated_total, at=reserved_at)
                    breaker.record_failure()
                    attempts.append((provider.id, failure.reason))
                    log = logger.warning if failure.config_error else logger.info
                    log(
                        "Trợ lý LLM lỗi: nha=%s model=%s ly_do=%s status=%s ma=%s tre_ms=%d",
                        provider.id, provider.model, failure.reason, failure.status, failure.detail,
                        int((time.perf_counter() - started) * 1000),
                    )
                    continue
                except BaseException:
                    # Bị hủy giữa chừng (orchestrator hết giờ cả lượt, client ngắt): không phải lỗi
                    # của nhà. Trả lượt thử của cầu dao, nếu không cờ "đang thử" giữ mãi và nhà này
                    # bị bỏ qua tới khi restart. Phần giữ chỗ trong sổ để nguyên (nhà có thể đã tính).
                    if holds_trial:
                        breaker.release_trial()
                    raise
                budget.adjust_tokens(
                    result.prompt_tokens + result.completion_tokens - estimated_total, at=reserved_at,
                )
                breaker.record_success()
                logger.info(
                    "Trợ lý LLM ok: nha=%s model=%s token_vao=%d token_ra=%d tool=%d tre_ms=%d",
                    result.provider, result.model, result.prompt_tokens, result.completion_tokens,
                    len(result.tool_calls), int((time.perf_counter() - started) * 1000),
                )
                return result
        finally:
            self._semaphore.release()
        raise LlmUnavailable("Mọi nhà cung cấp LLM đều không dùng được", attempts)

    async def _call(
        self,
        provider: ProviderConfig,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        max_tokens: int,
        user_hash: str | None,
    ) -> LlmResult:
        headers = {"Content-Type": "application/json", **provider.extra_headers}
        api_key = (self._env.get(provider.api_key_env) or "").strip() if provider.api_key_env else ""
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
        body: dict[str, Any] = {
            "model": provider.model,
            "messages": messages,
            "temperature": TEMPERATURE,
            "max_tokens": max_tokens,
            **provider.extra_body,
        }
        if tools and provider.supports_tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        if user_hash and provider.sends_user:
            body["user"] = user_hash

        try:
            # Timeout của httpx tính riêng từng bước (kết nối, đọc...), không phải tổng thời gian;
            # spec 7.4 cần "20 giây mỗi request" nên đặt thêm hạn tổng bằng asyncio.timeout.
            async with asyncio.timeout(self._timeout_s):
                response = await self._get_client().post(
                    f"{provider.base_url}/chat/completions", json=body, headers=headers, timeout=self._timeout_s,
                )
        except (httpx.TimeoutException, TimeoutError) as exc:
            raise _ProviderFailure("TIMEOUT") from exc
        except (httpx.InvalidURL, httpx.UnsupportedProtocol, ValueError) as exc:
            # Base URL hỏng (thiếu http://, ký tự lạ): lỗi cấu hình, không phải lỗi mạng. httpx ném
            # các lỗi này ngoài nhánh HTTPError (InvalidURL, ValueError) nên phải bắt riêng.
            raise _ProviderFailure("CAU_HINH_SAI", config_error=True) from exc
        except httpx.HTTPError as exc:
            raise _ProviderFailure("LOI_MANG") from exc

        budget = self._budgets[provider.id]
        status = response.status_code
        if status != 200:
            try:
                payload: Any = response.json()
            except ValueError:
                payload = None
            detail = _ma_loi_ngan(payload)
            if status == 429:
                if _het_han_muc_ngay(payload, response.text):
                    budget.block_today()
                    raise _ProviderFailure("HET_HAN_MUC_NGAY", status, detail=detail)
                budget.block_for(_retry_after(response))
                raise _ProviderFailure("QUA_NHANH", status, detail=detail)
            if status == 402:
                # OpenRouter: số dư âm chặn cả model free — không tự hết trong ngày.
                budget.block_today()
                raise _ProviderFailure("CAN_THANH_TOAN", status, detail=detail)
            if status == 408 or status >= 500:
                raise _ProviderFailure("LOI_TAM_THOI", status, detail=detail)
            # 400/401/403/404/422...: key sai, model bị gỡ, tham số không hỗ trợ — lỗi cấu hình.
            raise _ProviderFailure("CAU_HINH_SAI", status, config_error=True, detail=detail)

        try:
            payload = response.json()
        except ValueError as exc:
            raise _ProviderFailure("JSON_HONG", status) from exc
        if not isinstance(payload, dict):
            raise _ProviderFailure("JSON_HONG", status)
        if payload.get("error"):
            # OpenRouter có lúc trả 200 kèm lỗi của nhà phía sau.
            raise _ProviderFailure("LOI_TRONG_BODY", status, detail=_ma_loi_ngan(payload))
        choices = payload.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise _ProviderFailure("KHONG_CO_CHOICE", status)
        choice = choices[0]
        if choice.get("finish_reason") == "error" or choice.get("error"):
            raise _ProviderFailure("FINISH_ERROR", status)
        message = choice.get("message")
        if not isinstance(message, dict):
            raise _ProviderFailure("KHONG_CO_CHOICE", status)
        tool_calls = _parse_tool_calls(message.get("tool_calls"))
        text = _clean_text(message.get("content"))
        if text is None and not tool_calls:
            raise _ProviderFailure("TRA_LOI_RONG", status)

        usage = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
        prompt_tokens = usage.get("prompt_tokens")
        completion_tokens = usage.get("completion_tokens")
        if not isinstance(prompt_tokens, int):
            prompt_tokens = estimate_tokens(messages, tools)
        if not isinstance(completion_tokens, int):
            completion_tokens = max(1, len(text or "") // 3)
        model = payload.get("model") if isinstance(payload.get("model"), str) and payload.get("model") else provider.model
        return LlmResult(
            text=text,
            tool_calls=tool_calls,
            provider=provider.id,
            model=model,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
        )

    def _get_client(self) -> httpx.AsyncClient:
        # Tạo lười để client gắn với event loop đang chạy (uvicorn), không phải lúc import.
        if self._client is None:
            self._client = httpx.AsyncClient(transport=self._transport, timeout=self._timeout_s)
        return self._client

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None


@lru_cache(maxsize=1)
def get_llm_router() -> LlmRouter:
    """Router dùng chung cho cả tiến trình, dựng từ ``AssistantSettings`` + biến môi trường."""
    settings = get_settings()
    env = os.environ
    providers = build_providers(settings.provider_order, env)
    public_extra = build_providers(settings.public_extra_providers, env)
    if not providers and not public_extra:
        logger.warning("Trợ lý không có nhà cung cấp LLM nào dùng được — chỉ trả câu mẫu")
    return LlmRouter(
        providers,
        public_extra=public_extra,
        timeout_s=settings.llm_timeout_s,
        max_concurrent=settings.max_concurrent_llm,
        env=env,
    )
