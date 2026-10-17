"""Sổ hạn mức nhà cung cấp, ngắt tạm và giới hạn theo người dùng — đồng hồ giả, không chờ thật."""

from dataclasses import replace
from datetime import datetime, timezone

from app.services.assistant.llm.providers import PROVIDER_CATALOG
from app.services.assistant.llm.quota import (
    CircuitBreaker,
    ProviderBudget,
    UserRateLimiter,
)


class FakeClock:
    """Đồng hồ tua được: test điều khiển "bây giờ" thay vì sleep."""

    def __init__(self, start: datetime) -> None:
        self.now = start.timestamp()

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _utc(*args: int) -> datetime:
    return datetime(*args, tzinfo=timezone.utc)


def _config(**limits):
    base = PROVIDER_CATALOG["groq_gptoss120b"]
    values = {"rpm": None, "rpd": None, "tpm": None, "tpd": None, "reset_tz": "UTC"}
    values.update(limits)
    return replace(base, **values)


class TestProviderBudget:
    def test_chan_khi_het_request_moi_phut_roi_mo_lai_sau_60_giay(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        budget = ProviderBudget(_config(rpm=2), clock)
        budget.record(10, 5)
        clock.advance(1)
        budget.record(10, 5)
        assert budget.can_spend(10) is False
        clock.advance(59.5)  # request đầu đã quá 60 giây
        assert budget.can_spend(10) is True

    def test_chan_khi_vuot_token_moi_phut(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        budget = ProviderBudget(_config(tpm=8000), clock)
        budget.record(6000, 1000)
        assert budget.can_spend(1000) is True
        assert budget.can_spend(1001) is False

    def test_het_rpd_thi_chan_den_ngay_moi_utc(self):
        clock = FakeClock(_utc(2026, 10, 15, 23, 58, 0))
        budget = ProviderBudget(_config(rpd=3), clock)
        for _ in range(3):
            budget.record(1, 1)
        assert budget.can_spend(1) is False
        clock.advance(61)  # 23:59:01 — vẫn cùng ngày
        assert budget.can_spend(1) is False
        clock.advance(60)  # 00:00:01 UTC ngày 16
        assert budget.can_spend(1) is True
        assert budget.day_requests == 0

    def test_het_tpd_thi_chan_va_reset_theo_gio_thai_binh_duong(self):
        # 06:59 UTC ngày 16/10 = 23:59 ngày 15/10 giờ Los Angeles (PDT, UTC-7).
        clock = FakeClock(_utc(2026, 10, 16, 6, 59, 0))
        budget = ProviderBudget(_config(tpd=1000, reset_tz="America/Los_Angeles"), clock)
        budget.record(900, 100)
        assert budget.can_spend(1) is False
        clock.advance(120)  # 07:01 UTC: cùng ngày UTC nhưng đã sang ngày mới ở Los Angeles
        assert budget.can_spend(1) is True
        assert budget.day_tokens == 0

    def test_adjust_tokens_sua_uoc_luong_thanh_so_that(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        budget = ProviderBudget(_config(tpm=8000, tpd=200000), clock)
        reserved_at = budget.record(3000, 0)  # giữ chỗ theo ước lượng
        clock.advance(2)
        budget.adjust_tokens(-2000, at=reserved_at)  # thật ra chỉ tốn 1000
        assert budget.tokens_last_minute() == 1000
        assert budget.day_tokens == 1000
        assert budget.day_requests == 1  # sửa token không tính thêm request
        clock.advance(59)  # mục gốc và mục sửa cùng hết hạn
        assert budget.tokens_last_minute() == 0

    def test_block_for_va_block_today(self):
        clock = FakeClock(_utc(2026, 10, 15, 22, 0, 0))
        budget = ProviderBudget(_config(), clock)
        budget.block_for(30)
        assert budget.can_spend(1) is False
        clock.advance(31)
        assert budget.can_spend(1) is True
        budget.block_today()
        clock.advance(3600)  # 23:00:31 — vẫn ngày 15
        assert budget.can_spend(1) is False
        clock.advance(3600)  # 00:00:31 ngày 16
        assert budget.can_spend(1) is True

    def test_khong_gioi_han_khi_cau_hinh_none(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        budget = ProviderBudget(_config(), clock)
        for _ in range(500):
            budget.record(10_000, 10_000)
        assert budget.can_spend(1_000_000) is True


class TestProviderBudgetSuaUocLuong:
    """Bổ sung ngoài brief: mục sửa token không được làm lệch cửa sổ phút hay sổ ngày."""

    def test_sua_xen_ke_hai_request_khong_tru_oan_request_sau(self):
        # A giữ chỗ lúc t0, B lúc t0+10, rồi mới sửa A. Khi A hết hạn (t0+60) mà B còn,
        # cửa sổ phút phải còn đúng phần của B — mục sửa của A không được ở lại trừ vào B.
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        budget = ProviderBudget(_config(tpm=8000), clock)
        at_a = budget.record(3000, 0)
        clock.advance(10)
        budget.record(3000, 0)
        clock.advance(2)
        budget.adjust_tokens(-2000, at=at_a)
        assert budget.tokens_last_minute() == 4000
        clock.advance(50)  # t0+62: A (và mục sửa của A) đã hết hạn, B (t0+10) còn
        assert budget.tokens_last_minute() == 3000

    def test_sua_sau_khi_muc_goc_het_han_khong_dong_vao_cua_so_phut(self):
        # Request chạy lâu hơn 60 giây: mục giữ chỗ đã rơi khỏi cửa sổ, nên mục sửa cũng không
        # được tính vào cửa sổ hiện tại (nếu không sẽ trừ oan request mới).
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        budget = ProviderBudget(_config(tpm=8000), clock)
        at_a = budget.record(3000, 0)
        clock.advance(61)
        budget.record(1000, 0)
        budget.adjust_tokens(-2000, at=at_a)
        assert budget.tokens_last_minute() == 1000

    def test_sua_qua_nua_dem_khong_tru_vao_so_ngay_moi(self):
        clock = FakeClock(_utc(2026, 10, 15, 23, 59, 50))
        budget = ProviderBudget(_config(tpd=200000), clock)
        at_a = budget.record(3000, 0)  # giữ chỗ hôm 15
        clock.advance(20)  # 00:00:10 ngày 16 — sổ ngày đã về 0
        budget.record(500, 0)
        budget.adjust_tokens(-2000, at=at_a)  # sửa cho request của hôm qua
        assert budget.day_tokens == 500


class TestCircuitBreaker:
    def test_mo_sau_3_loi_lien_tiep_roi_half_open_cho_dung_mot_request(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        breaker = CircuitBreaker(failure_threshold=3, cooldown_s=60, clock=clock)
        for _ in range(2):
            breaker.record_failure()
        assert breaker.allow() is True and breaker.state == "closed"
        breaker.record_failure()
        assert breaker.state == "open"
        assert breaker.allow() is False
        clock.advance(60)
        assert breaker.allow() is True  # request thử
        assert breaker.state == "half_open"
        assert breaker.allow() is False  # chỉ một request thử tại một thời điểm

    def test_thu_thanh_cong_thi_dong(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        breaker = CircuitBreaker(clock=clock)
        for _ in range(3):
            breaker.record_failure()
        clock.advance(60)
        assert breaker.allow() is True
        breaker.record_success()
        assert breaker.state == "closed"
        assert breaker.allow() is True

    def test_thu_that_bai_thi_mo_them_mot_chu_ky(self):
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        breaker = CircuitBreaker(clock=clock)
        for _ in range(3):
            breaker.record_failure()
        clock.advance(60)
        assert breaker.allow() is True
        breaker.record_failure()
        assert breaker.state == "open"
        clock.advance(59)
        assert breaker.allow() is False
        clock.advance(1)
        assert breaker.allow() is True

    def test_thanh_cong_xen_giua_thi_dem_lai_tu_dau(self):
        breaker = CircuitBreaker(clock=FakeClock(_utc(2026, 10, 15, 8, 0, 0)))
        breaker.record_failure()
        breaker.record_failure()
        breaker.record_success()
        breaker.record_failure()
        breaker.record_failure()
        assert breaker.state == "closed"

    def test_tra_luot_thu_khi_request_thu_bi_huy(self):
        # Bổ sung ngoài brief: request thử bị hủy giữa chừng (hết giờ cả lượt) không được giữ
        # cờ "đang thử" mãi, nếu không nhà này bị bỏ qua tới khi restart.
        clock = FakeClock(_utc(2026, 10, 15, 8, 0, 0))
        breaker = CircuitBreaker(clock=clock)
        for _ in range(3):
            breaker.record_failure()
        clock.advance(60)
        assert breaker.allow() is True
        breaker.release_trial()
        assert breaker.state == "half_open"
        assert breaker.allow() is True  # được thử lại
        assert breaker.allow() is False

    def test_release_trial_khi_dong_khong_lam_gi(self):
        breaker = CircuitBreaker(clock=FakeClock(_utc(2026, 10, 15, 8, 0, 0)))
        breaker.record_failure()
        breaker.release_trial()
        breaker.record_failure()
        breaker.record_failure()
        assert breaker.state == "open"  # release_trial không xóa đếm lỗi


class TestUserRateLimiter:
    def test_gioi_han_moi_phut_theo_tung_nguoi(self):
        clock = FakeClock(_utc(2026, 10, 15, 3, 0, 0))
        limiter = UserRateLimiter(per_minute=2, per_day=100, clock=clock)
        assert limiter.try_acquire("a") is True
        assert limiter.try_acquire("a") is True
        assert limiter.try_acquire("a") is False
        assert limiter.try_acquire("b") is True  # người khác không bị ảnh hưởng
        clock.advance(60)
        assert limiter.try_acquire("a") is True

    def test_gioi_han_ngay_reset_luc_nua_dem_gio_viet_nam(self):
        # 16:59 UTC = 23:59 giờ Việt Nam (UTC+7).
        clock = FakeClock(_utc(2026, 10, 15, 16, 59, 0))
        limiter = UserRateLimiter(per_minute=100, per_day=2, clock=clock)
        assert limiter.try_acquire("a") is True
        assert limiter.try_acquire("a") is True
        assert limiter.try_acquire("a") is False
        clock.advance(120)  # 17:01 UTC = 00:01 ngày 16 giờ Việt Nam
        assert limiter.try_acquire("a") is True

    def test_qua_nua_dem_van_giu_gioi_han_moi_phut(self):
        # Bổ sung ngoài brief: sang ngày mới chỉ xóa đếm ngày; 60 giây trượt vẫn tính tiếp,
        # nếu không người dùng gửi gấp đôi giới hạn/phút quanh nửa đêm.
        clock = FakeClock(_utc(2026, 10, 15, 16, 59, 50))  # 23:59:50 giờ Việt Nam
        limiter = UserRateLimiter(per_minute=2, per_day=100, clock=clock)
        assert limiter.try_acquire("a") is True
        assert limiter.try_acquire("a") is True
        clock.advance(15)  # 00:00:05 ngày mới, chưa đủ 60 giây
        assert limiter.try_acquire("a") is False
        clock.advance(46)  # 61 giây sau hai câu đầu
        assert limiter.try_acquire("a") is True
