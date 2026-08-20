"""
HTTP client gọi cic-service để lấy điểm tín dụng CIC + dữ liệu thô.

Thiết kế fail-open: khi cic-service không khả dụng, trả None — pipeline scoring
tiếp tục với missing indicators thay vì chặn luồng.

v14: gọi `?chiTiet=true` và parse cả `hoSo` (10 trường tín dụng thô) thay vì
chỉ `diemCic`. Trả dict thay vì int.
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
    """Client gọi cic-service (port 8082) lấy điểm + dữ liệu tín dụng theo CCCD."""

    def __init__(
        self,
        base_url: str = CIC_BASE_URL,
        timeout: float = CIC_TIMEOUT_SECONDS,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    async def tra_diem_cic(self, so_cccd: str) -> dict | None:
        """Tra điểm CIC và dữ liệu tín dụng thô theo số CCCD.

        Returns:
            Dict với 11 keys (cic_score + 9 CIC raw + ty_le_su_dung_the tính từ
            duNoTheTinDung/hanMucThe) nếu thành công. None nếu fail-open.
        """
        url = f"{self.base_url}/api/v1/diem-tin-dung/{so_cccd}?chiTiet=true"
        cccd_che = _che_cccd(so_cccd)
        bat_dau = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                response = await http.get(url)

            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            if response.status_code != 200:
                logger.warning(
                    "cic-service tra_diem_cic cccd=%s status=%d latency_ms=%.0f result=non_200",
                    cccd_che, response.status_code, do_tre_ms,
                )
                return None

            data = response.json()
            ho_so = data.get("hoSo") or {}

            han_muc = ho_so.get("hanMucThe")
            du_no_the = ho_so.get("duNoTheTinDung")
            ty_le_su_dung = (
                du_no_the / han_muc * 100
                if han_muc and han_muc > 0 and du_no_the is not None
                else None
            )

            logger.info(
                "cic-service tra_diem_cic cccd=%s status=200 latency_ms=%.0f "
                "result=success ho_so_fields=%d",
                cccd_che, do_tre_ms, len(ho_so),
            )
            return {
                "cic_score": data["diemCic"],
                "so_lan_tre_han": ho_so.get("soLanTreHan24Thang"),
                "thang_tu_tre_gan_nhat": ho_so.get("soThangTuLanTreGanNhat"),
                "tong_du_no": ho_so.get("tongDuNo"),
                "du_no_the_tin_dung": ho_so.get("duNoTheTinDung"),
                "ty_le_su_dung_the": ty_le_su_dung,
                "so_lan_tra_cuu": ho_so.get("soLanTraCuu6Thang"),
                "so_hop_dong_dang_co": ho_so.get("soHopDongDangCo"),
                "so_thang_quan_he": ho_so.get("soThangQuanHe"),
                "nhom_no_cao_nhat": ho_so.get("nhomNoCaoNhat"),
            }

        except (httpx.HTTPError, ValueError, KeyError) as loi:
            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            logger.warning(
                "cic-service tra_diem_cic cccd=%s latency_ms=%.0f result=error "
                "error_class=%s: %s",
                cccd_che, do_tre_ms, type(loi).__name__, loi,
            )
            return None
