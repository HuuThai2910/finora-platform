"""Relay gọi service bằng token người dùng: allowlist, header, ánh xạ lỗi, timeout, không log bí mật."""

import logging

import httpx
import pytest

from app.services.assistant.relay import FinoraApiClient, ServiceName
from app.services.assistant.tools.base import RouteRule, ToolError

from .helpers import make_settings, route_transport

LOAN = "http://loan.test"
HO_SO = "LA-0F9C3C1E2A4B5C6D7E8F"
RULES = (
    RouteRule("GET", ServiceName.LOAN, r"/api/v1/loan-applications/me"),
    RouteRule("GET", ServiceName.LOAN, r"/api/v1/loan-applications/LA-[A-Z0-9]{6,32}"),
    RouteRule("POST", ServiceName.LOAN, r"/api/v1/loan-products/\d{1,9}/repayment-previews"),
    RouteRule("GET", ServiceName.PAYMENT, r"/api/v1/wallets/me"),
)


def _client(transport, token="tok"):
    return FinoraApiClient(make_settings(), token, RULES, transport=transport)


async def _get_ho_so(status, body):
    transport = route_transport({("GET", f"{LOAN}/api/v1/loan-applications/{HO_SO}"): (status, body)})
    async with _client(transport) as client:
        return await client.get(ServiceName.LOAN, f"/api/v1/loan-applications/{HO_SO}")


class TestThanhCong:
    @pytest.mark.asyncio
    async def test_get_tra_json_va_gui_token_nguoi_dung(self):
        transport = route_transport({
            ("GET", f"{LOAN}/api/v1/loan-applications/me"): (200, {"content": [{"applicationNumber": HO_SO}]}),
        })
        async with _client(transport, token="token-cua-nguoi-vay") as client:
            data = await client.get(
                ServiceName.LOAN, "/api/v1/loan-applications/me", {"size": 10, "status": None}
            )

        assert data == {"content": [{"applicationNumber": HO_SO}]}
        request = transport.requests[0]
        assert request.headers["Authorization"] == "Bearer token-cua-nguoi-vay"
        assert request.headers["X-Client-Type"] == "assistant"
        # Bộ lọc None bị bỏ, không gửi "status=None".
        assert str(request.url) == f"{LOAN}/api/v1/loan-applications/me?size=10"

    @pytest.mark.asyncio
    async def test_post_gui_body_json(self):
        url = f"{LOAN}/api/v1/loan-products/7/repayment-previews"
        transport = route_transport({("POST", url): (200, {"totalPayable": 33_000_000})})
        async with _client(transport) as client:
            data = await client.post(
                ServiceName.LOAN,
                "/api/v1/loan-products/7/repayment-previews",
                {"amount": 30_000_000, "termMonths": 12, "expectedDisbursementDate": "2026-10-20"},
            )

        assert data == {"totalPayable": 33_000_000}
        assert transport.requests[0].read() == (
            b'{"amount":30000000,"termMonths":12,"expectedDisbursementDate":"2026-10-20"}'
        )

    @pytest.mark.asyncio
    async def test_body_rong_tra_none(self):
        assert await _get_ho_so(204, None) is None

    @pytest.mark.asyncio
    async def test_dung_dung_base_url_cua_tung_service(self):
        transport = route_transport({("GET", "http://payment.test/api/v1/wallets/me"): (200, {"available": 5})})
        async with _client(transport) as client:
            assert await client.get(ServiceName.PAYMENT, "/api/v1/wallets/me") == {"available": 5}


class TestAllowlist:
    @pytest.mark.parametrize(
        ("method", "service", "path"),
        [
            ("GET", ServiceName.LOAN, "/api/v1/admin/loan-applications"),
            ("GET", ServiceName.LOAN, "/api/v1/loan-applications/LA-1/../../admin/users"),
            ("GET", ServiceName.LOAN, "/api/v1/loan-applications/me?size=1000"),
            ("GET", ServiceName.LOAN, "/api/v1/loan-applications/me#x"),
            # Đúng path nhưng sai service hoặc sai method.
            ("GET", ServiceName.INVESTMENT, "/api/v1/loan-applications/me"),
            ("POST", ServiceName.LOAN, "/api/v1/loan-applications/me"),
        ],
    )
    @pytest.mark.asyncio
    async def test_chan_va_khong_gui_request(self, method, service, path):
        transport = route_transport({})
        async with _client(transport) as client:
            with pytest.raises(ToolError) as error:
                if method == "GET":
                    await client.get(service, path)
                else:
                    await client.post(service, path, {})

        assert error.value.code == "KHONG_DUOC_PHEP"
        assert transport.requests == []


class TestAnhXaLoi:
    @pytest.mark.parametrize(
        ("status", "body", "code", "upstream"),
        [
            (401, {"code": "UNAUTHORIZED"}, "PHIEN_HET_HAN", "UNAUTHORIZED"),
            (403, {"code": "FORBIDDEN"}, "KHONG_TIM_THAY", "FORBIDDEN"),
            (404, {"code": "APPLICATION_NOT_FOUND"}, "KHONG_TIM_THAY", "APPLICATION_NOT_FOUND"),
            (500, {"code": "INTERNAL_ERROR"}, "DICH_VU_LOI", "INTERNAL_ERROR"),
            (502, b"<html>Bad gateway</html>", "DICH_VU_LOI", None),
            (400, {"code": "PIN_LOCKED", "message": "x"}, "DU_LIEU_KHONG_HOP_LE", "PIN_LOCKED"),
            (409, None, "DU_LIEU_KHONG_HOP_LE", None),
            (302, None, "DU_LIEU_KHONG_HOP_LE", None),
        ],
    )
    @pytest.mark.asyncio
    async def test_status_thanh_ma_loi(self, status, body, code, upstream):
        with pytest.raises(ToolError) as error:
            await _get_ho_so(status, body)

        assert error.value.code == code
        assert error.value.upstream_code == upstream
        assert error.value.user_message

    @pytest.mark.asyncio
    async def test_403_va_404_cung_mot_cau(self):
        with pytest.raises(ToolError) as forbidden:
            await _get_ho_so(403, None)
        with pytest.raises(ToolError) as missing:
            await _get_ho_so(404, None)
        assert forbidden.value.user_message == missing.value.user_message

    @pytest.mark.asyncio
    async def test_fineract_chua_san_sang_co_cau_rieng(self):
        with pytest.raises(ToolError) as error:
            await _get_ho_so(503, {"code": "CORE_LENDING_UNAVAILABLE"})
        assert error.value.code == "DICH_VU_LOI"
        assert "30 giây" in error.value.user_message

    @pytest.mark.asyncio
    async def test_json_hong(self):
        with pytest.raises(ToolError) as error:
            await _get_ho_so(200, b"{khong-phai-json")
        assert error.value.code == "DU_LIEU_KHONG_HOP_LE"

    @pytest.mark.asyncio
    async def test_timeout(self):
        with pytest.raises(ToolError) as error:
            await _get_ho_so(0, httpx.ReadTimeout("het gio"))
        assert error.value.code == "DICH_VU_LOI"

    @pytest.mark.asyncio
    async def test_loi_mang(self):
        with pytest.raises(ToolError) as error:
            await _get_ho_so(0, httpx.ConnectError("tu choi ket noi"))
        assert error.value.code == "DICH_VU_LOI"


class TestTimeout:
    @pytest.mark.asyncio
    async def test_tool_thuong_8_giay_tool_cham_30_giay(self):
        transport = route_transport({
            ("GET", f"{LOAN}/api/v1/loan-applications/me"): (200, {}),
            ("POST", f"{LOAN}/api/v1/loan-products/7/repayment-previews"): (200, {}),
        })
        async with _client(transport) as client:
            await client.get(ServiceName.LOAN, "/api/v1/loan-applications/me")
            await client.post(ServiceName.LOAN, "/api/v1/loan-products/7/repayment-previews", {}, slow=True)

        assert transport.requests[0].extensions["timeout"]["read"] == 8.0
        assert transport.requests[1].extensions["timeout"]["read"] == 30.0

    @pytest.mark.asyncio
    async def test_doc_timeout_tu_settings(self):
        transport = route_transport({("GET", f"{LOAN}/api/v1/loan-applications/me"): (200, {})})
        settings = make_settings(tool_timeout_s=2.5, slow_tool_timeout_s=12.0)
        async with FinoraApiClient(settings, "tok", RULES, transport=transport) as client:
            await client.get(ServiceName.LOAN, "/api/v1/loan-applications/me", slow=True)
        assert transport.requests[0].extensions["timeout"]["read"] == 12.0


class TestKhongLogBiMat:
    @pytest.mark.asyncio
    async def test_log_khong_co_token_path_that_hay_body(self, caplog):
        caplog.set_level(logging.DEBUG, logger="app.services.assistant.relay")
        transport = route_transport({
            ("GET", f"{LOAN}/api/v1/loan-applications/{HO_SO}"): (500, {"message": "noi-dung-nhay-cam"}),
        })
        async with _client(transport, token="bi-mat-123") as client:
            with pytest.raises(ToolError):
                await client.get(ServiceName.LOAN, f"/api/v1/loan-applications/{HO_SO}")
            with pytest.raises(ToolError):
                await client.get(ServiceName.LOAN, "/api/v1/admin/users/CHEN-LENH")

        assert "assistant relay" in caplog.text
        assert "bi-mat-123" not in caplog.text
        assert "noi-dung-nhay-cam" not in caplog.text
        assert HO_SO not in caplog.text
        assert "CHEN-LENH" not in caplog.text
