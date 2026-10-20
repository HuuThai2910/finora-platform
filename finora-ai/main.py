"""
FINORA AI Service — Entry Point
Chạy bằng: uvicorn main:app --reload --port 8000
"""
import logging
import os
from pathlib import Path


def _load_dotenv() -> None:
    """Nạp `.env` cạnh main.py vào môi trường (KEY=VALUE, # là comment).

    Tự viết vài dòng thay vì thêm dependency python-dotenv. Biến đã có sẵn
    trong môi trường được giữ nguyên để còn override từ ngoài khi cần.
    """
    env_file = Path(__file__).parent / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


_load_dotenv()

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import (
    assistant_router,
    config_router,
    credit_router,
    ekyc_router,
    fraud_router,
)
from app.api.errors import register_error_handlers
from app.security import CREDIT_SCORE, EKYC_OCR, FRAUD_DETECT, ROLE_ADMIN, api_docs_enabled, require_any
from app.services.assistant.llm.router import get_llm_router
from app.services.assistant.orchestrator import get_orchestrator
from app.services.assistant.settings import get_settings

# Uvicorn chỉ cấu hình logger của chính nó; logger của app không có handler nên
# log INFO (như dòng chẩn đoán OCR) bị nuốt. Bật handler gốc để nhìn thấy chúng.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
# httpx tự ghi một dòng INFO cho mỗi request kèm URL đầy đủ; URL trợ lý gọi Loan/Investment có mã hồ sơ
# của người dùng (và URL Cloudflare có account id), trong khi log của trợ lý chỉ được chứa siêu dữ liệu
# (LEGAL-DATA-01).
logging.getLogger("httpx").setLevel(logging.WARNING)

logger = logging.getLogger(__name__)


@asynccontextmanager
async def _khoi_dong(_app: FastAPI):
    """Dựng sẵn Trợ lý FINORA (đọc YAML kiến thức, dựng chỉ mục BM25) khi trợ lý đang bật.

    Để câu hỏi đầu tiên không phải chờ đọc kho kiến thức. Lỗi ở đây chỉ ghi log: một file kiến
    thức hỏng không được kéo sập cả dịch vụ chấm điểm tín dụng; request /assistant đầu tiên sẽ
    dựng lại và lỗi hiện rõ ở đó. Lúc tắt thì đóng client HTTP của router LLM (nếu đã dựng).
    """
    if get_settings().enabled:
        try:
            get_orchestrator()
        except Exception:
            logger.exception("Không dựng được Trợ lý FINORA lúc khởi động; thử lại ở request đầu tiên")
    yield
    # Chỉ đóng khi router đã được dựng: gọi get_llm_router() ở đây sẽ dựng mới một router chỉ để đóng.
    if get_llm_router.cache_info().currsize:
        try:
            await get_llm_router().aclose()
        except Exception:
            logger.warning("Không đóng được client HTTP của router LLM lúc tắt", exc_info=True)


_docs = api_docs_enabled()
app = FastAPI(
    title="FINORA AI Service",
    description="Dịch vụ Trí tuệ Nhân tạo cho hệ thống P2P Lending FINORA",
    version="1.0.0",
    lifespan=_khoi_dong,
    # Swagger/OpenAPI chỉ mở ở local/e2e/demo (D35).
    docs_url="/docs" if _docs else None,
    redoc_url="/redoc" if _docs else None,
    openapi_url="/openapi.json" if _docs else None,
)

# Lỗi có mã (xác thực, trợ lý) trả `{"code", "message"}` như service Java.
register_error_handlers(app)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# === Register Routers ===
# Mọi endpoint nghiệp vụ cần access token Keycloak (D35): Loan chấm điểm bằng service account, admin web dùng phiên
# đăng nhập; chỉ User được OCR CCCD; chỉ admin đọc/sửa cấu hình chấm điểm.
app.include_router(credit_router.router, prefix="/api/v1/ai/credit", tags=["Credit Scoring"],
                   dependencies=[Depends(require_any(CREDIT_SCORE, ROLE_ADMIN))])
app.include_router(config_router.router, prefix="/api/v1/ai/config", tags=["Product Config"],
                   dependencies=[Depends(require_any(ROLE_ADMIN))])
app.include_router(ekyc_router.router, prefix="/api/v1/ai/ekyc", tags=["eKYC"],
                   dependencies=[Depends(require_any(EKYC_OCR))])
app.include_router(fraud_router.router, prefix="/api/v1/ai/fraud", tags=["Fraud Detection"],
                   dependencies=[Depends(require_any(FRAUD_DETECT, ROLE_ADMIN))])
# Trợ lý FINORA tự kiểm JWT của người hỏi trong router (app/services/auth/) vì phải giữ token để gọi service thay họ.
app.include_router(assistant_router.router, prefix="/api/v1/ai/assistant", tags=["Assistant"])


@app.get("/health")
async def health_check():
    return {"status": "FINORA AI Service is running!"}
