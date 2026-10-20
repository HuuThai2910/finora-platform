""".env.example phải liệt kê đủ biến của trợ lý (spec mục 16) và không chứa giá trị bí mật.

Người dựng máy mới (local, VM Azure) chỉ nhìn file này để biết cần đặt gì; thiếu một biến là trợ lý
chạy với mặc định mà không ai hay (ví dụ URL service trỏ localhost bên trong container).
"""

import re
from pathlib import Path

FILE_ENV_MAU = Path(__file__).resolve().parents[2] / ".env.example"

BIEN_TRO_LY = {
    "ASSISTANT_ENABLED",
    "ASSISTANT_TOOLSETS",
    "ASSISTANT_PROVIDERS",
    "ASSISTANT_PUBLIC_PROVIDERS",
    "ASSISTANT_HASH_SALT",
    "ASSISTANT_USER_RATE_PER_MINUTE",
    "ASSISTANT_USER_RATE_PER_DAY",
    "ASSISTANT_LLM_TIMEOUT_SECONDS",
    "ASSISTANT_TOOL_TIMEOUT_SECONDS",
    "ASSISTANT_SLOW_TOOL_TIMEOUT_SECONDS",
    "ASSISTANT_TURN_TIMEOUT_SECONDS",
    "ASSISTANT_MAX_CONCURRENT_LLM",
    "ASSISTANT_MODEL_OLLAMA_LOCAL",
    "CLOUDFLARE_ACCOUNT_ID",
    "CLOUDFLARE_API_TOKEN",
    "GROQ_API_KEY",
    "OPENROUTER_API_KEY",
    "GEMINI_API_KEY",
    "OLLAMA_BASE_URL",
    "KEYCLOAK_JWKS_URL",
    "KEYCLOAK_ISSUERS",
    "LOAN_SERVICE_URL",
    "PAYMENT_SERVICE_URL",
    "INVESTMENT_SERVICE_URL",
    "USER_SERVICE_URL",
    "NOTIFICATION_SERVICE_URL",
}

# Biến chứa khóa/bí mật: trong file mẫu phải để trống.
BIEN_BI_MAT = (
    "CLOUDFLARE_ACCOUNT_ID",
    "CLOUDFLARE_API_TOKEN",
    "GROQ_API_KEY",
    "OPENROUTER_API_KEY",
    "GEMINI_API_KEY",
    "ASSISTANT_HASH_SALT",
)


def _noi_dung() -> str:
    return FILE_ENV_MAU.read_text(encoding="utf-8")


def test_co_du_bien_cua_tro_ly():
    # Tính cả dòng mẫu đang comment ("# OLLAMA_BASE_URL=...") vì đó là biến tùy chọn có hướng dẫn.
    khai_bao = set(re.findall(r"^#?\s*([A-Z][A-Z0-9_]+)=", _noi_dung(), re.MULTILINE))
    assert BIEN_TRO_LY - khai_bao == set()


def test_bien_bi_mat_de_trong():
    noi_dung = _noi_dung()
    for ten in BIEN_BI_MAT:
        khop = re.search(rf"^{ten}=(.*)$", noi_dung, re.MULTILINE)
        assert khop is not None, f"{ten} phải có dòng khai báo (không comment)"
        assert khop.group(1).strip() == "", f"{ten} trong .env.example phải để trống"


def test_tro_ly_mac_dinh_tat():
    assert re.search(r"^ASSISTANT_ENABLED=false\s*$", _noi_dung(), re.MULTILINE)
