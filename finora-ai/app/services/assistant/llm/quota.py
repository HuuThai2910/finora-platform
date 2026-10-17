"""Bộ đếm hạn mức trong bộ nhớ cho lớp LLM của trợ lý (spec mục 7.3 và 11).

Ba thứ, đều chỉ sống trong tiến trình (uvicorn một worker nên không bị chia đôi bộ đếm):

* ``ProviderBudget`` — "sổ tay" mỗi nhà cung cấp đã dùng bao nhiêu trong phút/ngày, để không
  gửi request chắc chắn bị 429. Restart làm sổ về 0; nếu nhà thật sự đã hết, nó trả 429 và
  router khóa nhà đó, nên chỉ tốn đúng một request thừa.
* ``CircuitBreaker`` — "tạm không gọi nhà đang hỏng": 3 lỗi liên tiếp thì bỏ qua 60 giây rồi
  cho thử lại một request, tránh mỗi câu hỏi đều phải chờ timeout của một nhà đang sập.
* ``UserRateLimiter`` — mỗi người tối đa N câu/phút và M câu/ngày (ngày theo giờ Việt Nam).

Mọi hàm ở đây đồng bộ, không ``await`` giữa đọc và ghi bộ đếm, nên trong một event loop chúng
chạy trọn vẹn không bị coroutine khác chen vào (spec mục 11).

``clock`` trả epoch giây (mặc định ``time.time``); test truyền đồng hồ giả để tua thời gian.
"""

from __future__ import annotations

import time
from collections import deque
from collections.abc import Callable
from datetime import date, datetime
from zoneinfo import ZoneInfo

from app.services.assistant.llm.providers import ProviderConfig

Clock = Callable[[], float]

#: Cửa sổ trượt của hạn mức "mỗi phút".
_PHUT = 60.0
#: Người dùng là người Việt: "100 câu/ngày" phải hết ngày theo giờ Việt Nam, không theo UTC.
_GIO_VIET_NAM = ZoneInfo("Asia/Ho_Chi_Minh")


def _ngay_theo(tz: ZoneInfo, epoch: float) -> date:
    return datetime.fromtimestamp(epoch, tz).date()


class ProviderBudget:
    """Đếm request/token của một nhà theo cửa sổ phút trượt và theo ngày của nhà đó."""

    def __init__(self, config: ProviderConfig, clock: Clock = time.time) -> None:
        self._config = config
        self._clock = clock
        self._tz = ZoneInfo(config.reset_tz)
        self._minute_requests: deque[float] = deque()
        self._minute_tokens: deque[tuple[float, int]] = deque()
        self._day: date | None = None
        self._day_requests = 0
        self._day_tokens = 0
        self._blocked_until = 0.0
        self._blocked_day: date | None = None

    def _cuon(self, now: float) -> date:
        """Bỏ mục quá 60 giây khỏi cửa sổ phút; sang ngày mới (theo múi giờ của nhà) thì về 0."""
        cutoff = now - _PHUT
        while self._minute_requests and self._minute_requests[0] <= cutoff:
            self._minute_requests.popleft()
        while self._minute_tokens and self._minute_tokens[0][0] <= cutoff:
            self._minute_tokens.popleft()
        today = _ngay_theo(self._tz, now)
        if today != self._day:
            self._day = today
            self._day_requests = 0
            self._day_tokens = 0
        return today

    def can_spend(self, estimated_tokens: int) -> bool:
        """True nếu gửi thêm một request ước ``estimated_tokens`` token vẫn nằm trong hạn mức."""
        now = self._clock()
        today = self._cuon(now)
        if now < self._blocked_until or self._blocked_day == today:
            return False
        cfg = self._config
        if cfg.rpm is not None and len(self._minute_requests) + 1 > cfg.rpm:
            return False
        if cfg.rpd is not None and self._day_requests + 1 > cfg.rpd:
            return False
        if cfg.tpm is not None and self.tokens_last_minute() + estimated_tokens > cfg.tpm:
            return False
        return cfg.tpd is None or self._day_tokens + estimated_tokens <= cfg.tpd

    def record(self, prompt_tokens: int, completion_tokens: int) -> float:
        """Ghi một request đã gửi cùng số token của nó; trả mốc thời gian đã ghi."""
        now = self._clock()
        self._cuon(now)
        total = max(0, prompt_tokens) + max(0, completion_tokens)
        self._minute_requests.append(now)
        self._minute_tokens.append((now, total))
        self._day_requests += 1
        self._day_tokens += total
        return now

    def adjust_tokens(self, delta: int, *, at: float | None = None) -> None:
        """Sửa số token đã giữ chỗ (ước lượng) thành số thật, không tính thêm request.

        ``at`` là mốc của lần giữ chỗ (giá trị ``record`` trả về): mục sửa hết hạn cùng lúc với
        mục gốc nên cửa sổ phút không bị trừ oan cho request khác.

        Mục sửa được thêm vào cuối hàng đợi dù mốc ``at`` cũ hơn các mục đứng trước, nên hàng
        đợi không còn sắp theo thời gian; ``tokens_last_minute`` vì vậy lọc theo mốc thay vì cộng
        cả hàng đợi. Mục gốc đã rơi khỏi cửa sổ 60 giây (request chạy lâu) thì không thêm mục sửa
        vào cửa sổ; mục gốc thuộc ngày trước (theo múi giờ của nhà) thì không sửa sổ hôm nay.
        """
        if delta == 0:
            return
        now = self._clock()
        today = self._cuon(now)
        stamp = now if at is None else at
        if stamp > now - _PHUT:
            self._minute_tokens.append((stamp, delta))
        if _ngay_theo(self._tz, stamp) == today:
            self._day_tokens = max(0, self._day_tokens + delta)

    def block_for(self, seconds: float) -> None:
        """Nhà báo 429 theo phút (có/không có retry-after): không gọi trong ``seconds`` giây."""
        self._blocked_until = max(self._blocked_until, self._clock() + max(0.0, seconds))

    def block_today(self) -> None:
        """Nhà báo đã hết hạn mức ngày: khóa tới mốc reset kế tiếp theo múi giờ của nhà."""
        self._blocked_day = self._cuon(self._clock())

    def tokens_last_minute(self) -> int:
        now = self._clock()
        self._cuon(now)
        cutoff = now - _PHUT
        return max(0, sum(tokens for stamp, tokens in self._minute_tokens if stamp > cutoff))

    @property
    def day_requests(self) -> int:
        self._cuon(self._clock())
        return self._day_requests

    @property
    def day_tokens(self) -> int:
        self._cuon(self._clock())
        return self._day_tokens


class CircuitBreaker:
    """Ngắt tạm một nhà sau ``failure_threshold`` lỗi liên tiếp.

    Ba trạng thái: ``closed`` (gọi bình thường) → ``open`` (bỏ qua trong ``cooldown_s`` giây)
    → ``half_open`` (cho đúng một request thử). Thử thành công thì đóng lại; thất bại thì mở
    tiếp một chu kỳ ``cooldown_s`` mới.
    """

    def __init__(self, failure_threshold: int = 3, cooldown_s: float = 60.0, clock: Clock = time.time) -> None:
        self._threshold = failure_threshold
        self._cooldown = cooldown_s
        self._clock = clock
        self._failures = 0
        self._opened_at: float | None = None
        self._trial_in_flight = False

    @property
    def state(self) -> str:
        if self._opened_at is None:
            return "closed"
        if self._trial_in_flight or self._clock() - self._opened_at >= self._cooldown:
            return "half_open"
        return "open"

    def allow(self) -> bool:
        """Có được gọi nhà này bây giờ không. Ở half-open chỉ trả True cho đúng một lần gọi."""
        if self._opened_at is None:
            return True
        if self._clock() - self._opened_at < self._cooldown:
            return False
        if self._trial_in_flight:
            return False
        self._trial_in_flight = True
        return True

    def record_success(self) -> None:
        self._failures = 0
        self._opened_at = None
        self._trial_in_flight = False

    def release_trial(self) -> None:
        """Trả lượt thử khi request thử bị hủy giữa chừng (không thành công, cũng không lỗi).

        Hủy thường do orchestrator hết giờ cả lượt hoặc client ngắt, không phải lỗi của nhà, nên
        không đổi đếm lỗi hay mốc mở. Không gọi thì cờ "đang thử" giữ mãi và nhà này bị bỏ qua
        tới khi restart.
        """
        self._trial_in_flight = False

    def record_failure(self) -> None:
        if self._opened_at is not None:
            # Request thử ở half-open lại lỗi: mở thêm một chu kỳ mới tính từ bây giờ.
            self._opened_at = self._clock()
            self._trial_in_flight = False
            return
        self._failures += 1
        if self._failures >= self._threshold:
            self._opened_at = self._clock()


class UserRateLimiter:
    """Giới hạn số câu hỏi của mỗi người: ``per_minute`` câu/60 giây trượt và ``per_day`` câu/ngày.

    ``user_key`` nên là hash người dùng (``audit.hash_user``), không phải user id thô, để bộ nhớ
    tiến trình cũng không giữ định danh.
    """

    def __init__(self, per_minute: int, per_day: int, clock: Clock = time.time) -> None:
        self._per_minute = per_minute
        self._per_day = per_day
        self._clock = clock
        self._minute: dict[str, deque[float]] = {}
        self._day_counts: dict[str, int] = {}
        self._day: date | None = None

    def try_acquire(self, user_key: str) -> bool:
        """Trả True và tính một câu nếu còn hạn mức; False thì không tính gì."""
        now = self._clock()
        today = _ngay_theo(_GIO_VIET_NAM, now)
        cutoff = now - _PHUT
        if today != self._day:
            # Sang ngày mới: xóa đếm ngày. Cửa sổ 60 giây thì giữ (xóa luôn sẽ cho người dùng gửi
            # gấp đôi giới hạn/phút quanh nửa đêm), chỉ dọn cửa sổ đã hết hạn để bộ nhớ không
            # phình theo số người từng hỏi.
            self._day = today
            self._day_counts.clear()
            for key in list(self._minute):
                stale = self._minute[key]
                while stale and stale[0] <= cutoff:
                    stale.popleft()
                if not stale:
                    del self._minute[key]
        window = self._minute.setdefault(user_key, deque())
        while window and window[0] <= cutoff:
            window.popleft()
        if len(window) >= self._per_minute or self._day_counts.get(user_key, 0) >= self._per_day:
            return False
        window.append(now)
        self._day_counts[user_key] = self._day_counts.get(user_key, 0) + 1
        return True
