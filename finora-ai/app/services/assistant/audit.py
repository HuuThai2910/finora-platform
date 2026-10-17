"""Log siêu dữ liệu cho mỗi lượt và mỗi đánh giá của Trợ lý FINORA.

Log dùng để đo hạn mức nhà cung cấp, độ trễ và chất lượng (tỷ lệ fallback, lỗi công cụ). Theo
LEGAL-DATA-01, log KHÔNG BAO GIỜ chứa câu hỏi, câu trả lời, token đăng nhập hay user id gốc: user id
được băm HMAC-SHA256 bằng `ASSISTANT_HASH_SALT`, nên không có muối thì không lần ngược ra người dùng.

Mỗi bản ghi là một dòng JSON để sau này lọc bằng `grep assistant_turn | jq` trên VM.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
import re
from collections.abc import Iterable, Sequence

logger = logging.getLogger("finora.assistant.audit")

# Màn hình và turnId do client gửi lên; chỉ ghi khi đúng khuôn để client không nhét được dữ liệu tùy ý vào log.
_MAN_HOP_LE = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,39}")
_TURN_ID_HOP_LE = re.compile(r"[0-9A-Fa-f-]{8,64}")


def hash_user(user_id: str, salt: str) -> str:
    """Băm user id để nối các lượt của cùng một người trong log mà không lưu id thật."""
    return hmac.new(salt.encode("utf-8"), user_id.encode("utf-8"), hashlib.sha256).hexdigest()


def _ghi(su_kien: str, ban_ghi: dict[str, object]) -> None:
    logger.info("%s %s", su_kien, json.dumps({"event": su_kien, **ban_ghi}, ensure_ascii=False, separators=(",", ":")))


def log_turn(
    *,
    turn_id: str,
    user_hash: str,
    toolsets: Iterable[str],
    surface: str,
    screen: str | None,
    mode: str,
    tools: Sequence[str],
    provider: str | None,
    model: str | None,
    prompt_tokens: int,
    completion_tokens: int,
    latency_ms: int,
    error_code: str | None,
) -> None:
    """Một dòng log cho một lượt hỏi đáp (kể cả lượt lỗi, lượt trùng và lượt hết giờ)."""
    _ghi(
        "assistant_turn",
        {
            "turnId": turn_id,
            "user": user_hash,
            "toolsets": sorted(toolsets),
            "surface": surface,
            "screen": screen if screen and _MAN_HOP_LE.fullmatch(screen) else None,
            "mode": mode,
            "tools": list(tools),
            "provider": provider,
            "model": model,
            "promptTokens": prompt_tokens,
            "completionTokens": completion_tokens,
            "latencyMs": latency_ms,
            "errorCode": error_code,
        },
    )


def log_feedback(*, turn_id: str, user_hash: str, rating: str) -> None:
    """Ghi đánh giá up/down của người dùng cho một lượt; không lưu nội dung lượt đó."""
    _ghi(
        "assistant_feedback",
        {
            "turnId": turn_id if _TURN_ID_HOP_LE.fullmatch(turn_id) else None,
            "user": user_hash,
            "rating": rating,
        },
    )
