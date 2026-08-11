"""
HTTP client gọi cic-service để lấy điểm tín dụng CIC.

Thiết kế fail-open: khi cic-service không khả dụng (timeout, lỗi mạng, 5xx,
payload thiếu field), trả None thay vì raise — pipeline scoring tiếp tục với
missing indicator (`cic_score_missing`) thay vì chặn luồng chấm điểm.

Không retry: mỗi lần gọi CIC thêm latency vào luồng scoring vốn đã async;
một lần timeout `CIC_TIMEOUT_SECONDS` (mặc định 3 giây) là đủ chấp nhận cho
use case này (xem docs/superpowers/plans/2026-08-11-cic-score-integration.md).
"""

import logging
import os
import time

import httpx

logger = logging.getLogger(__name__)

CIC_BASE_URL = os.getenv("CIC_SERVICE_URL", "http://localhost:8082")
CIC_TIMEOUT_SECONDS = float(os.getenv("CIC_TIMEOUT_SECONDS", "3.0"))


def _che_cccd(so_cccd: str) -> str:
    """Che số CCCD khi log: chỉ giữ 3 ký tự đầu/cuối, giấu phần giữa (PII)."""
    if len(so_cccd) <= 6:
        return "***"
    return f"{so_cccd[:3]}...{so_cccd[-3:]}"


class CicClient:
    """Client gọi cic-service (port 8082) lấy điểm tín dụng theo số CCCD."""

    def __init__(
        self,
        base_url: str = CIC_BASE_URL,
        timeout: float = CIC_TIMEOUT_SECONDS,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    async def tra_diem_cic(self, so_cccd: str) -> int | None:
        """Tra điểm CIC theo số CCCD.

        Args:
            so_cccd: Số CCCD 12 chữ số của người vay.

        Returns:
            `diemCic` (int, 150-750) nếu cic-service trả 200 hợp lệ.
            None nếu timeout, lỗi mạng, HTTP status khác 200, hoặc payload
            không có field `diemCic` — không bao giờ raise ra ngoài.
        """
        url = f"{self.base_url}/api/v1/diem-tin-dung/{so_cccd}"
        cccd_che = _che_cccd(so_cccd)
        bat_dau = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                response = await http.get(url)

            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            if response.status_code != 200:
                logger.warning(
                    "cic-service tra_diem_cic cccd=%s status=%d latency_ms=%.0f result=non_200",
                    cccd_che,
                    response.status_code,
                    do_tre_ms,
                )
                return None

            diem = response.json()["diemCic"]
            logger.info(
                "cic-service tra_diem_cic cccd=%s status=200 latency_ms=%.0f result=success",
                cccd_che,
                do_tre_ms,
            )
            return diem

        except (httpx.HTTPError, ValueError, KeyError) as loi:
            # httpx.HTTPError: timeout/lỗi mạng/lỗi kết nối; ValueError: JSON không hợp lệ;
            # KeyError: payload thiếu field diemCic. Đây là các lỗi fail-open đã lường trước.
            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            logger.warning(
                "cic-service tra_diem_cic cccd=%s latency_ms=%.0f result=error error_class=%s: %s",
                cccd_che,
                do_tre_ms,
                type(loi).__name__,
                loi,
            )
            return None
