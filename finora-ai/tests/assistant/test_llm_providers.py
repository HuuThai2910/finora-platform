"""Danh mục nhà cung cấp LLM: đúng spec 7.1 và dựng danh sách theo env."""

import logging

from app.services.assistant.llm.providers import (
    PROVIDER_CATALOG,
    Lane,
    build_providers,
    model_override_env,
)

ENV_DU = {
    "CLOUDFLARE_ACCOUNT_ID": "acc123",
    "CLOUDFLARE_API_TOKEN": "cf-token",
    "GROQ_API_KEY": "gsk-test",
    "OPENROUTER_API_KEY": "sk-or-test",
    "GEMINI_API_KEY": "gm-test",
    "OLLAMA_BASE_URL": "http://localhost:11434/v1/",
}


def test_danh_muc_co_du_bay_nha_cua_spec():
    assert set(PROVIDER_CATALOG) == {
        "cloudflare_gemma26b", "groq_gptoss120b", "groq_qwen27b", "groq_gptoss20b",
        "openrouter_gemma31b", "gemini_gemma31b", "ollama_local",
    }
    for provider_id, config in PROVIDER_CATALOG.items():
        assert config.id == provider_id


def test_base_url_va_model_dung_tai_lieu_chinh_thuc():
    cf = PROVIDER_CATALOG["cloudflare_gemma26b"]
    assert cf.base_url == "https://api.cloudflare.com/client/v4/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1"
    assert cf.model == "@cf/google/gemma-4-26b-a4b-it"
    assert PROVIDER_CATALOG["groq_gptoss120b"].base_url == "https://api.groq.com/openai/v1"
    assert PROVIDER_CATALOG["groq_qwen27b"].model == "qwen/qwen3.8-27b"
    assert PROVIDER_CATALOG["groq_gptoss20b"].model == "openai/gpt-oss-20b"
    assert PROVIDER_CATALOG["openrouter_gemma31b"].base_url == "https://openrouter.ai/api/v1"
    assert PROVIDER_CATALOG["openrouter_gemma31b"].model == "google/gemma-4-31b-it:free"
    assert PROVIDER_CATALOG["gemini_gemma31b"].base_url == "https://generativelanguage.googleapis.com/v1beta/openai"
    assert PROVIDER_CATALOG["ollama_local"].base_url == "{OLLAMA_BASE_URL}"


def test_han_muc_theo_spec():
    groq = PROVIDER_CATALOG["groq_gptoss120b"]
    assert (groq.rpm, groq.rpd, groq.tpm, groq.tpd) == (30, 1000, 8000, 200000)
    orr = PROVIDER_CATALOG["openrouter_gemma31b"]
    assert (orr.rpm, orr.rpd) == (20, 50)
    cf = PROVIDER_CATALOG["cloudflare_gemma26b"]
    assert cf.rpm == 300 and cf.rpd is None  # hạn mức ngày tính bằng neuron, xử lý qua 429 mã 3036
    gemini = PROVIDER_CATALOG["gemini_gemma31b"]
    assert gemini.reset_tz == "America/Los_Angeles"


def test_gemini_chi_duoc_lan_public():
    assert PROVIDER_CATALOG["gemini_gemma31b"].lanes == frozenset({Lane.PUBLIC})
    for provider_id in ("cloudflare_gemma26b", "groq_gptoss120b", "groq_qwen27b", "groq_gptoss20b",
                        "openrouter_gemma31b", "ollama_local"):
        assert Lane.USER_DATA in PROVIDER_CATALOG[provider_id].lanes


def test_tham_so_suy_luan_va_header_rieng():
    assert PROVIDER_CATALOG["groq_qwen27b"].extra_body["reasoning_effort"] == "none"
    assert PROVIDER_CATALOG["groq_gptoss120b"].extra_body["reasoning_effort"] == "low"
    assert PROVIDER_CATALOG["groq_gptoss20b"].extra_body["reasoning_effort"] == "low"
    headers = PROVIDER_CATALOG["openrouter_gemma31b"].extra_headers
    assert headers["X-Title"] == "FINORA"
    assert headers["HTTP-Referer"].startswith("https://")
    assert PROVIDER_CATALOG["openrouter_gemma31b"].sends_user is True
    assert PROVIDER_CATALOG["ollama_local"].sends_user is False


def test_build_providers_giu_thu_tu_va_dien_base_url():
    providers = build_providers(
        ["groq_gptoss120b", "cloudflare_gemma26b", "ollama_local"], ENV_DU,
    )
    assert [p.id for p in providers] == ["groq_gptoss120b", "cloudflare_gemma26b", "ollama_local"]
    assert providers[1].base_url == "https://api.cloudflare.com/client/v4/accounts/acc123/ai/v1"
    assert providers[2].base_url == "http://localhost:11434/v1"  # bỏ "/" cuối để ghép đường dẫn


def test_build_providers_bo_nha_thieu_key_hoac_thieu_bien():
    env = {"GROQ_API_KEY": "gsk", "CLOUDFLARE_API_TOKEN": "cf", "OPENROUTER_API_KEY": "  "}
    providers = build_providers(
        ["cloudflare_gemma26b", "groq_qwen27b", "openrouter_gemma31b", "ollama_local"], env,
    )
    # Cloudflare thiếu ACCOUNT_ID, OpenRouter key rỗng, Ollama không có OLLAMA_BASE_URL.
    assert [p.id for p in providers] == ["groq_qwen27b"]


def test_build_providers_bo_id_la_va_trung(caplog):
    with caplog.at_level(logging.WARNING):
        providers = build_providers(["groq_gptoss120b", "khong_ton_tai", "groq_gptoss120b", " "], ENV_DU)
    assert [p.id for p in providers] == ["groq_gptoss120b"]
    assert "khong_ton_tai" in caplog.text
    assert "gsk-test" not in caplog.text


def test_doi_model_bang_bien_moi_truong():
    env = {**ENV_DU, model_override_env("ollama_local"): "gemma4:e4b"}
    assert model_override_env("ollama_local") == "ASSISTANT_MODEL_OLLAMA_LOCAL"
    (ollama,) = build_providers(["ollama_local"], env)
    assert ollama.model == "gemma4:e4b"
    assert PROVIDER_CATALOG["ollama_local"].model == "qwen3.5:4b"  # danh mục gốc không bị đổi


def test_provider_config_bam_duoc():
    # frozen dataclass dùng được làm khóa/so sánh dù có trường Mapping.
    assert hash(PROVIDER_CATALOG["groq_gptoss120b"]) == hash(PROVIDER_CATALOG["groq_gptoss120b"])


# ── bổ sung ngoài brief: các lỗi tìm thấy khi rà code ─────────────────────────


def test_base_url_khong_phai_http_thi_bo_nha_va_ghi_warning(caplog):
    # Gõ thiếu "http://" ở OLLAMA_BASE_URL: httpx ném ValueError (không phải HTTPError) lúc gửi,
    # nên phải chặn ngay khi dựng danh sách thay vì để lọt thành lỗi 500 ở lượt hỏi.
    env = {**ENV_DU, "OLLAMA_BASE_URL": "localhost:11434/v1"}
    with caplog.at_level(logging.WARNING):
        providers = build_providers(["ollama_local", "groq_gptoss120b"], env)
    assert [p.id for p in providers] == ["groq_gptoss120b"]
    assert "ollama_local" in caplog.text
    assert "localhost:11434" not in caplog.text  # không chép giá trị biến vào log
