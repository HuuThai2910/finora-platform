"""
HTTP client gọi finora-user để lấy số CCCD của người vay theo ID hồ sơ.

CIC tra cứu theo số CCCD, nhưng Loan Service không được giữ PII đó (xem
`BorrowerProfileResult` — contract cố ý loại CCCD). Vì vậy AI tự hỏi finora-user
khi cần, thay vì để số CCCD đi vòng qua Loan.

Thiết kế fail-open giống `cic_client`: không lấy được CCCD thì trả None và pipeline
chấm điểm tiếp tục với chỉ báo thiếu, chứ không chặn hồ sơ. Điểm khi đó thấp hơn
thực chất — đó là đánh đổi có chủ ý, còn hơn từ chối chấm.

Xác thực bằng service account (client_credentials) của `finora-user-client`, cần
client role `user:admin:read_all`.
"""

import logging
import os
import time

import httpx

logger = logging.getLogger(__name__)

USER_BASE_URL = os.getenv("USER_SERVICE_URL", "http://localhost:8085")
USER_TIMEOUT_SECONDS = float(os.getenv("USER_TIMEOUT_SECONDS", "3.0"))

KEYCLOAK_TOKEN_URL = os.getenv(
    "KEYCLOAK_TOKEN_URL",
    "http://localhost:8180/realms/finora/protocol/openid-connect/token",
)
KEYCLOAK_CLIENT_ID = os.getenv("KEYCLOAK_CLIENT_ID", "finora-user-client")
KEYCLOAK_CLIENT_SECRET = os.getenv("KEYCLOAK_CLIENT_SECRET", "")

# Đổi token sớm hơn hạn để một request khởi hành sát giờ không hết hiệu lực giữa đường.
TOKEN_LEEWAY_SECONDS = 30.0


def _che_cccd(so_cccd: str) -> str:
    """Che số CCCD khi log: chỉ giữ 3 ký tự đầu/cuối, giấu phần giữa (PII)."""
    if len(so_cccd) <= 6:
        return "***"
    return f"{so_cccd[:3]}...{so_cccd[-3:]}"


class UserClient:
    """Client gọi finora-user (port 8085) lấy CCCD theo ID người dùng."""

    def __init__(
        self,
        base_url: str = USER_BASE_URL,
        timeout: float = USER_TIMEOUT_SECONDS,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._token: str | None = None
        self._token_het_han: float = 0.0

    async def _lay_token(self) -> str | None:
        """Token service account, dùng lại cho tới khi gần hết hạn."""
        if self._token and time.monotonic() < self._token_het_han:
            return self._token

        if not KEYCLOAK_CLIENT_SECRET:
            logger.warning(
                "finora-user lay_token result=missing_secret "
                "(chua dat KEYCLOAK_CLIENT_SECRET nen khong tra duoc CCCD)"
            )
            return None

        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                response = await http.post(
                    KEYCLOAK_TOKEN_URL,
                    data={
                        "grant_type": "client_credentials",
                        "client_id": KEYCLOAK_CLIENT_ID,
                        "client_secret": KEYCLOAK_CLIENT_SECRET,
                    },
                )

            if response.status_code != 200:
                logger.warning(
                    "keycloak lay_token status=%d result=non_200", response.status_code
                )
                return None

            data = response.json()
            self._token = data["access_token"]
            self._token_het_han = (
                time.monotonic() + float(data.get("expires_in", 60)) - TOKEN_LEEWAY_SECONDS
            )
            return self._token

        except (httpx.HTTPError, ValueError, KeyError) as loi:
            logger.warning(
                "keycloak lay_token result=error error_class=%s: %s",
                type(loi).__name__, loi,
            )
            return None

    async def tra_cccd(self, borrower_id: str) -> str | None:
        """Tra số CCCD của một người dùng theo ID hồ sơ nội bộ.

        Returns:
            Số CCCD nếu người dùng đã hoàn tất eKYC. None khi chưa định danh,
            không tra được, hoặc finora-user lỗi (fail-open).
        """
        token = await self._lay_token()
        if not token:
            return None

        url = f"{self.base_url}/api/v1/admin/users/{borrower_id}"
        bat_dau = time.monotonic()
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                response = await http.get(
                    url, headers={"Authorization": f"Bearer {token}"}
                )

            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            if response.status_code != 200:
                logger.warning(
                    "finora-user tra_cccd borrower_id=%s status=%d latency_ms=%.0f "
                    "result=non_200",
                    borrower_id, response.status_code, do_tre_ms,
                )
                return None

            so_cccd = (response.json() or {}).get("idNumber")
            if not so_cccd:
                # Tài khoản chưa quét eKYC thì chưa có CCCD — chuyện bình thường,
                # không phải lỗi hệ thống nên chỉ ghi mức info.
                logger.info(
                    "finora-user tra_cccd borrower_id=%s latency_ms=%.0f result=no_cccd",
                    borrower_id, do_tre_ms,
                )
                return None

            logger.info(
                "finora-user tra_cccd borrower_id=%s cccd=%s latency_ms=%.0f result=success",
                borrower_id, _che_cccd(so_cccd), do_tre_ms,
            )
            return so_cccd

        except (httpx.HTTPError, ValueError, KeyError) as loi:
            do_tre_ms = (time.monotonic() - bat_dau) * 1000
            logger.warning(
                "finora-user tra_cccd borrower_id=%s latency_ms=%.0f result=error "
                "error_class=%s: %s",
                borrower_id, do_tre_ms, type(loi).__name__, loi,
            )
            return None
