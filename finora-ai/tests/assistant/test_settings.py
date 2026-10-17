"""Đọc cấu hình trợ lý từ biến môi trường."""

from app.services.assistant.principal import Toolset
from app.services.assistant.settings import AssistantSettings, get_settings


class TestFromEnv:
    def test_mac_dinh_khi_env_rong(self):
        settings = AssistantSettings.from_env({})

        assert settings.enabled is False
        assert settings.toolsets == frozenset({Toolset.BORROWER, Toolset.INVESTOR, Toolset.ADMIN})
        assert settings.provider_order == (
            "cloudflare_gemma26b",
            "groq_gptoss120b",
            "groq_qwen27b",
            "groq_gptoss20b",
            "openrouter_gemma31b",
        )
        assert settings.public_extra_providers == ("gemini_gemma31b",)
        assert settings.loan_url == "http://localhost:8081"
        assert settings.payment_url == "http://localhost:8082"
        assert settings.investment_url == "http://localhost:8084"
        assert settings.user_url == "http://localhost:8085"
        assert settings.notification_url == "http://localhost:8086"
        assert settings.user_rate_per_minute == 10
        assert settings.user_rate_per_day == 100
        assert settings.llm_timeout_s == 20.0
        assert settings.tool_timeout_s == 8.0
        assert settings.slow_tool_timeout_s == 30.0
        assert settings.turn_timeout_s == 40.0
        assert settings.max_concurrent_llm == 2

    def test_doc_gia_tri_tu_env(self):
        settings = AssistantSettings.from_env({
            "ASSISTANT_ENABLED": "TRUE",
            "ASSISTANT_TOOLSETS": " borrower , ADMIN ",
            "ASSISTANT_PROVIDERS": "groq_gptoss120b, ollama_local",
            "ASSISTANT_PUBLIC_PROVIDERS": "gemini_gemma31b",
            "ASSISTANT_HASH_SALT": "muoi-rieng",
            "LOAN_SERVICE_URL": "http://loan:8081/",
            "PAYMENT_SERVICE_URL": "http://payment:8082",
            "INVESTMENT_SERVICE_URL": "http://investment:8084",
            "USER_SERVICE_URL": "http://user:8085",
            "NOTIFICATION_SERVICE_URL": "http://notification:8086",
            "ASSISTANT_USER_RATE_PER_MINUTE": "5",
            "ASSISTANT_USER_RATE_PER_DAY": "50",
            "ASSISTANT_TOOL_TIMEOUT_SECONDS": "4.5",
            "ASSISTANT_MAX_CONCURRENT_LLM": "1",
        })

        assert settings.enabled is True
        assert settings.toolsets == frozenset({Toolset.BORROWER, Toolset.ADMIN})
        assert settings.provider_order == ("groq_gptoss120b", "ollama_local")
        assert settings.hash_salt == "muoi-rieng"
        assert settings.loan_url == "http://loan:8081"
        assert settings.notification_url == "http://notification:8086"
        assert settings.user_rate_per_minute == 5
        assert settings.user_rate_per_day == 50
        assert settings.tool_timeout_s == 4.5
        assert settings.max_concurrent_llm == 1

    def test_tat_bang_false(self):
        for raw in ("false", "0", "off", "No"):
            assert AssistantSettings.from_env({"ASSISTANT_ENABLED": raw}).enabled is False

    def test_gia_tri_bool_la_dung_mac_dinh(self):
        assert AssistantSettings.from_env({"ASSISTANT_ENABLED": "co"}).enabled is False

    def test_toolsets_bo_qua_ten_la(self):
        settings = AssistantSettings.from_env({"ASSISTANT_TOOLSETS": "INVESTOR,KHACH"})
        assert settings.toolsets == frozenset({Toolset.INVESTOR})

    def test_toolsets_bo_trong_la_du_ba_vai_tro(self):
        # Muốn tắt hẳn thì dùng ASSISTANT_ENABLED=false; dòng trống trong .env không được tắt ngầm.
        settings = AssistantSettings.from_env({"ASSISTANT_TOOLSETS": "  "})
        assert settings.toolsets == frozenset(Toolset)

    def test_so_sai_kieu_dung_mac_dinh(self):
        settings = AssistantSettings.from_env({
            "ASSISTANT_USER_RATE_PER_MINUTE": "mười",
            "ASSISTANT_USER_RATE_PER_DAY": "-3",
            "ASSISTANT_LLM_TIMEOUT_SECONDS": "0",
            "ASSISTANT_TURN_TIMEOUT_SECONDS": "nan",
        })
        assert settings.user_rate_per_minute == 10
        assert settings.user_rate_per_day == 100
        assert settings.llm_timeout_s == 20.0
        assert settings.turn_timeout_s == 40.0

    def test_thieu_salt_thi_sinh_ngau_nhien(self):
        first = AssistantSettings.from_env({}).hash_salt
        second = AssistantSettings.from_env({"ASSISTANT_HASH_SALT": " "}).hash_salt
        assert len(first) == 32
        assert first != second


class TestGetSettings:
    def test_doc_mot_lan_tu_os_environ(self, monkeypatch):
        monkeypatch.setenv("ASSISTANT_ENABLED", "true")
        monkeypatch.setenv("LOAN_SERVICE_URL", "http://loan:8081")
        get_settings.cache_clear()
        try:
            first = get_settings()
            monkeypatch.setenv("LOAN_SERVICE_URL", "http://khac:1")
            assert get_settings() is first
            assert first.enabled is True
            assert first.loan_url == "http://loan:8081"
        finally:
            get_settings.cache_clear()
