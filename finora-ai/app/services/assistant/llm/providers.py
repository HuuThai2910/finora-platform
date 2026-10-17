"""Danh mục nhà cung cấp LLM miễn phí của Trợ lý FINORA (spec mục 7.1).

Mọi nhà ở đây đều nói chuẩn OpenAI ``POST {base_url}/chat/completions`` nên router chỉ cần
một đường gọi. Khác nhau giữa các nhà nằm hết trong dữ liệu cấu hình: base URL, model,
biến môi trường chứa key, hạn mức miễn phí và vài tham số riêng (``extra_body``).

Hạn mức ghi ở đây là "sổ tay" để router không gửi request chắc chắn bị từ chối; nguồn
chuẩn vẫn là nhà cung cấp. Số liệu kiểm trên trang chính thức ngày 2026-10-15; nhà nào
đổi hạn mức thì sửa đúng một dòng ở đây.
"""

from __future__ import annotations

import logging
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, replace
from enum import Enum
from types import MappingProxyType
from typing import Any

logger = logging.getLogger(__name__)


class Lane(str, Enum):
    """Làn dữ liệu: quyết định nhà cung cấp nào được nhìn thấy nội dung của lượt hỏi.

    ``USER_DATA`` có dữ kiện của người dùng (đã lọc định danh) nên chỉ đi qua nhà không
    huấn luyện trên dữ liệu; ``PUBLIC`` chỉ có kiến thức chung/KPI tổng hợp.

    Làn do công cụ/đường xử lý quyết định, không do LLM chọn.
    """

    USER_DATA = "USER_DATA"
    PUBLIC = "PUBLIC"


def _rong() -> Mapping[str, Any]:
    # MappingProxyType chỉ đọc: cấu hình dùng chung giữa mọi request, không ai được sửa giữa chừng.
    return MappingProxyType({})


@dataclass(frozen=True)
class ProviderConfig:
    """Cấu hình một nhà cung cấp + model.

    ``base_url`` có thể chứa chỗ trống ``{TEN_BIEN}`` (ví dụ ``{CLOUDFLARE_ACCOUNT_ID}``),
    được ``build_providers`` điền từ biến môi trường. ``rpm/rpd/tpm/tpd`` là request/phút,
    request/ngày, token/phút, token/ngày; ``None`` nghĩa là nhà không công bố hoặc không giới
    hạn theo đơn vị đó. ``reset_tz`` là múi giờ mà mốc "ngày mới" của nhà tính theo.
    """

    id: str
    base_url: str
    model: str
    api_key_env: str
    lanes: frozenset[Lane]
    rpm: int | None
    rpd: int | None
    tpm: int | None
    tpd: int | None
    reset_tz: str = "UTC"
    supports_tools: bool = True
    # hash=False: Mapping không băm được, mà ProviderConfig frozen vẫn cần băm được.
    extra_headers: Mapping[str, str] = field(default_factory=_rong, hash=False)
    extra_body: Mapping[str, Any] = field(default_factory=_rong, hash=False)
    # Chỉ OpenRouter nhận trường "user" (hash người dùng) để họ chống lạm dụng theo người;
    # Ollama ghi rõ không hỗ trợ, các nhà khác không cần nên không gửi.
    sends_user: bool = False


_HAI_LAN = frozenset({Lane.USER_DATA, Lane.PUBLIC})
_CHI_PUBLIC = frozenset({Lane.PUBLIC})

# Trang web FINORA khai cho OpenRouter (header HTTP-Referer) — chỉ dùng để họ ghi nguồn gọi.
OPENROUTER_REFERER = "https://finora-web-delta.vercel.app"

PROVIDER_CATALOG: dict[str, ProviderConfig] = {
    # Cloudflare tính hạn mức bằng "neuron" (10.000/ngày, reset 00:00 UTC), không phải request
    # hay token, nên sổ tay không đếm ngày: khi hết, Cloudflare trả 429 mã 3036 và router khóa
    # nhà này tới 00:00 UTC (ProviderBudget.block_today). 300 req/phút là hạn mức Text Generation.
    "cloudflare_gemma26b": ProviderConfig(
        id="cloudflare_gemma26b",
        base_url="https://api.cloudflare.com/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1",
        model="@cf/google/gemma-4-26b-a4b-it",
        api_key_env="CLOUDFLARE_API_TOKEN",
        lanes=_HAI_LAN,
        rpm=300,
        rpd=None,
        tpm=None,
        tpd=None,
    ),
    # Groq: mỗi model có quota riêng 30 req/phút, 1.000 req/ngày, 8K token/phút, 200K token/ngày.
    # gpt-oss không tắt được suy luận, mức thấp nhất là "low"; suy luận nằm ở trường riêng
    # "reasoning" nên không lẫn vào câu trả lời.
    "groq_gptoss120b": ProviderConfig(
        id="groq_gptoss120b",
        base_url="https://api.groq.com/openai/v1",
        model="openai/gpt-oss-120b",
        api_key_env="GROQ_API_KEY",
        lanes=_HAI_LAN,
        rpm=30,
        rpd=1000,
        tpm=8000,
        tpd=200000,
        extra_body=MappingProxyType({"reasoning_effort": "low"}),
    ),
    # Qwen 3.8 là model duy nhất của Groq nhận "none" (tắt hẳn suy luận) — đỡ tốn token/phút.
    "groq_qwen27b": ProviderConfig(
        id="groq_qwen27b",
        base_url="https://api.groq.com/openai/v1",
        model="qwen/qwen3.8-27b",
        api_key_env="GROQ_API_KEY",
        lanes=_HAI_LAN,
        rpm=30,
        rpd=1000,
        tpm=8000,
        tpd=200000,
        extra_body=MappingProxyType({"reasoning_effort": "none"}),
    ),
    "groq_gptoss20b": ProviderConfig(
        id="groq_gptoss20b",
        base_url="https://api.groq.com/openai/v1",
        model="openai/gpt-oss-20b",
        api_key_env="GROQ_API_KEY",
        lanes=_HAI_LAN,
        rpm=30,
        rpd=1000,
        tpm=8000,
        tpd=200000,
        extra_body=MappingProxyType({"reasoning_effort": "low"}),
    ),
    # OpenRouter free: 20 req/phút, 50 req/ngày cho CẢ tài khoản (mọi model :free cộng chung).
    # Endpoint duy nhất của model này là Google AI Studio: không huấn luyện, lưu 55 ngày.
    "openrouter_gemma31b": ProviderConfig(
        id="openrouter_gemma31b",
        base_url="https://openrouter.ai/api/v1",
        model="google/gemma-4-31b-it:free",
        api_key_env="OPENROUTER_API_KEY",
        lanes=_HAI_LAN,
        rpm=20,
        rpd=50,
        tpm=None,
        tpd=None,
        extra_headers=MappingProxyType({"HTTP-Referer": OPENROUTER_REFERER, "X-Title": "FINORA"}),
        sends_user=True,
    ),
    # Gemini API bản miễn phí ĐƯỢC dùng nội dung để huấn luyện và có người đọc, nên chỉ chạy
    # làn PUBLIC. Google không công bố hạn mức Gemma; số dưới là số diễn đàn, Task 1 đối chiếu
    # ở aistudio.google.com/rate-limit. Hạn mức ngày reset lúc nửa đêm giờ Thái Bình Dương.
    "gemini_gemma31b": ProviderConfig(
        id="gemini_gemma31b",
        base_url="https://generativelanguage.googleapis.com/v1beta/openai",
        model="gemma-4-31b-it",
        api_key_env="GEMINI_API_KEY",
        lanes=_CHI_PUBLIC,
        rpm=30,
        rpd=14400,
        tpm=16000,
        tpd=None,
        reset_tz="America/Los_Angeles",
    ),
    # Ollama chạy trên máy dev, dữ liệu không rời máy; không cần key. Chỉ bật khi có biến
    # OLLAMA_BASE_URL (ví dụ http://localhost:11434/v1). "none" tắt suy luận của qwen3.5.
    "ollama_local": ProviderConfig(
        id="ollama_local",
        base_url="{OLLAMA_BASE_URL}",
        model="qwen3.5:4b",
        api_key_env="",
        lanes=_HAI_LAN,
        rpm=None,
        rpd=None,
        tpm=None,
        tpd=None,
        extra_body=MappingProxyType({"reasoning_effort": "none"}),
    ),
}

_CHO_TRONG = re.compile(r"\{([A-Z0-9_]+)\}")
# Base URL sau khi điền phải là http(s)://host[...] và không có khoảng trắng/ký tự điều khiển.
_URL_HOP_LE = re.compile(r"https?://[^\s/?#]+\S*", re.IGNORECASE)


def _dien_cho_trong(template: str, env: Mapping[str, str]) -> str | None:
    """Điền ``{TEN_BIEN}`` từ env; thiếu biến nào thì trả None để bỏ nhà đó."""
    thieu: list[str] = []

    def _thay(match: re.Match[str]) -> str:
        value = (env.get(match.group(1)) or "").strip()
        if not value:
            thieu.append(match.group(1))
        return value

    filled = _CHO_TRONG.sub(_thay, template)
    return None if thieu else filled


def model_override_env(provider_id: str) -> str:
    """Tên biến môi trường cho phép đổi model của một nhà mà không sửa code.

    Ví dụ ``ASSISTANT_MODEL_OLLAMA_LOCAL=gemma4:e4b``. Model miễn phí đổi tên/biến mất khá
    thường xuyên, nên đổi được bằng env là cách giảm rủi ro ở spec mục 18.
    """
    return f"ASSISTANT_MODEL_{provider_id.upper()}"


def build_providers(ids: Sequence[str], env: Mapping[str, str]) -> list[ProviderConfig]:
    """Dựng danh sách nhà theo thứ tự ``ids``, bỏ nhà thiếu key hoặc thiếu biến trong base URL.

    Bỏ qua thay vì ném lỗi để trợ lý vẫn chạy với các nhà còn lại (hoặc chỉ câu mẫu); chỉ ghi
    tên biến bị thiếu, không bao giờ ghi giá trị key. Id lạ (gõ sai trong ASSISTANT_PROVIDERS)
    ghi WARNING vì đó là lỗi cấu hình cần sửa.
    """
    result: list[ProviderConfig] = []
    seen: set[str] = set()
    for raw in ids:
        provider_id = raw.strip()
        if not provider_id or provider_id in seen:
            continue
        seen.add(provider_id)
        base = PROVIDER_CATALOG.get(provider_id)
        if base is None:
            logger.warning("Bỏ nhà cung cấp LLM không có trong danh mục: %s", provider_id)
            continue
        if base.api_key_env and not (env.get(base.api_key_env) or "").strip():
            logger.info("Bỏ nhà cung cấp %s: thiếu biến %s", provider_id, base.api_key_env)
            continue
        base_url = _dien_cho_trong(base.base_url, env)
        if base_url is None:
            logger.info("Bỏ nhà cung cấp %s: thiếu biến trong base URL", provider_id)
            continue
        if not _URL_HOP_LE.fullmatch(base_url):
            # Ví dụ OLLAMA_BASE_URL=localhost:11434/v1 (thiếu "http://"): httpx sẽ ném lỗi không
            # phải HTTPError lúc gửi. Chặn ngay khi dựng để lỗi cấu hình lộ ra lúc khởi động.
            # Không ghi giá trị URL: nó có thể chứa account id.
            logger.warning("Bỏ nhà cung cấp %s: base URL không phải http(s)://", provider_id)
            continue
        model = (env.get(model_override_env(provider_id)) or "").strip() or base.model
        result.append(replace(base, base_url=base_url.rstrip("/"), model=model))
    return result
