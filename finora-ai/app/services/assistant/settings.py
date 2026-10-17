"""Cấu hình của trợ lý, đọc một lần từ biến môi trường (`finora-ai/.env`, spec mục 16).

Đọc qua `from_env(env)` thay vì `os.getenv` rải rác để test truyền thẳng một dict, không phải
sửa `os.environ`. Giá trị sai kiểu không làm sập service: ghi cảnh báo rồi dùng mặc định, vì
một lỗi gõ trong `.env` không nên kéo theo cả chấm điểm tín dụng (cùng tiến trình) chết theo.
"""

import logging
import math
import os
import secrets
from collections.abc import Mapping
from dataclasses import dataclass
from functools import lru_cache

from app.services.assistant.principal import Toolset

logger = logging.getLogger(__name__)

DEFAULT_PROVIDERS: tuple[str, ...] = (
    "cloudflare_gemma26b",
    "groq_gptoss120b",
    "groq_qwen27b",
    "groq_gptoss20b",
    "openrouter_gemma31b",
)
DEFAULT_PUBLIC_PROVIDERS: tuple[str, ...] = ("gemini_gemma31b",)

# Cổng local theo `.agents/rules/05-registry.md`; trong docker compose ghi đè bằng tên service.
DEFAULT_SERVICE_URLS: dict[str, str] = {
    "LOAN_SERVICE_URL": "http://localhost:8081",
    "PAYMENT_SERVICE_URL": "http://localhost:8082",
    "INVESTMENT_SERVICE_URL": "http://localhost:8084",
    "USER_SERVICE_URL": "http://localhost:8085",
    "NOTIFICATION_SERVICE_URL": "http://localhost:8086",
}

_TRUE = {"1", "true", "yes", "on"}
_FALSE = {"0", "false", "no", "off"}


@dataclass(frozen=True)
class AssistantSettings:
    enabled: bool
    toolsets: frozenset[Toolset]
    provider_order: tuple[str, ...]
    public_extra_providers: tuple[str, ...]
    hash_salt: str
    loan_url: str
    payment_url: str
    investment_url: str
    user_url: str
    notification_url: str
    user_rate_per_minute: int = 10
    user_rate_per_day: int = 100
    llm_timeout_s: float = 20.0
    tool_timeout_s: float = 8.0
    slow_tool_timeout_s: float = 30.0
    turn_timeout_s: float = 40.0
    max_concurrent_llm: int = 2

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "AssistantSettings":
        """Dựng cấu hình từ `env` (mặc định `os.environ`)."""
        source: Mapping[str, str] = os.environ if env is None else env
        return cls(
            # Mặc định TẮT: trợ lý gửi dữ liệu ra nhà cung cấp nước ngoài, nên chỉ bật khi
            # người vận hành chủ động đặt ASSISTANT_ENABLED=true.
            enabled=_read_bool(source, "ASSISTANT_ENABLED", False),
            toolsets=_read_toolsets(source.get("ASSISTANT_TOOLSETS")),
            provider_order=_read_list(source.get("ASSISTANT_PROVIDERS"), DEFAULT_PROVIDERS),
            public_extra_providers=_read_list(
                source.get("ASSISTANT_PUBLIC_PROVIDERS"), DEFAULT_PUBLIC_PROVIDERS
            ),
            hash_salt=_read_salt(source.get("ASSISTANT_HASH_SALT")),
            loan_url=_read_url(source, "LOAN_SERVICE_URL"),
            payment_url=_read_url(source, "PAYMENT_SERVICE_URL"),
            investment_url=_read_url(source, "INVESTMENT_SERVICE_URL"),
            user_url=_read_url(source, "USER_SERVICE_URL"),
            notification_url=_read_url(source, "NOTIFICATION_SERVICE_URL"),
            user_rate_per_minute=_read_int(source, "ASSISTANT_USER_RATE_PER_MINUTE", 10),
            user_rate_per_day=_read_int(source, "ASSISTANT_USER_RATE_PER_DAY", 100),
            llm_timeout_s=_read_float(source, "ASSISTANT_LLM_TIMEOUT_SECONDS", 20.0),
            tool_timeout_s=_read_float(source, "ASSISTANT_TOOL_TIMEOUT_SECONDS", 8.0),
            slow_tool_timeout_s=_read_float(source, "ASSISTANT_SLOW_TOOL_TIMEOUT_SECONDS", 30.0),
            turn_timeout_s=_read_float(source, "ASSISTANT_TURN_TIMEOUT_SECONDS", 40.0),
            max_concurrent_llm=_read_int(source, "ASSISTANT_MAX_CONCURRENT_LLM", 2),
        )


@lru_cache(maxsize=1)
def get_settings() -> AssistantSettings:
    """Cấu hình dùng chung của tiến trình. Test gọi `get_settings.cache_clear()` khi đổi env."""
    return AssistantSettings.from_env()


def _read_bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = (env.get(name) or "").strip().lower()
    if not raw:
        return default
    if raw in _TRUE:
        return True
    if raw in _FALSE:
        return False
    logger.warning("assistant settings name=%s result=invalid_bool dung_mac_dinh=%s", name, default)
    return default


def _read_list(raw: str | None, default: tuple[str, ...]) -> tuple[str, ...]:
    # Bỏ trống coi như chưa cấu hình. Nhà thiếu API key được Task 12 tự loại khi dựng router,
    # nên không cần cú pháp riêng để "tắt" một nhà.
    items = tuple(item.strip() for item in (raw or "").split(",") if item.strip())
    return items or default


def _read_toolsets(raw: str | None) -> frozenset[Toolset]:
    names = _read_list(raw, tuple(toolset.value for toolset in Toolset))
    result: set[Toolset] = set()
    for name in names:
        try:
            result.add(Toolset(name.upper()))
        except ValueError:
            logger.warning("assistant settings ASSISTANT_TOOLSETS bo_qua_gia_tri_la=%s", name)
    return frozenset(result)


def _read_salt(raw: str | None) -> str:
    salt = (raw or "").strip()
    if salt:
        return salt
    # Không có salt thì sinh ngẫu nhiên theo tiến trình: hash user trong log vẫn không đảo ngược
    # được, chỉ mất khả năng nối log qua các lần restart. Vẫn hơn dùng một salt cố định trong code.
    logger.warning("assistant settings ASSISTANT_HASH_SALT trong -> dung salt ngau nhien theo tien trinh")
    return secrets.token_hex(16)


def _read_url(env: Mapping[str, str], name: str) -> str:
    raw = (env.get(name) or "").strip() or DEFAULT_SERVICE_URLS[name]
    # Bỏ "/" cuối để relay ghép base + "/api/v1/..." không thành "//api".
    return raw.rstrip("/")


def _read_int(env: Mapping[str, str], name: str, default: int) -> int:
    raw = (env.get(name) or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        value = 0
    if value <= 0:
        logger.warning("assistant settings name=%s result=invalid_int dung_mac_dinh=%s", name, default)
        return default
    return value


def _read_float(env: Mapping[str, str], name: str, default: float) -> float:
    raw = (env.get(name) or "").strip()
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        value = 0.0
    if not (math.isfinite(value) and value > 0):
        logger.warning("assistant settings name=%s result=invalid_float dung_mac_dinh=%s", name, default)
        return default
    return value
