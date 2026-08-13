"""Test CIC client — mock HTTP, không cần cic-service thật."""

import httpx
import pytest

from app.services.cic_client import CicClient


@pytest.fixture
def client():
    return CicClient(base_url="http://localhost:8082")


class TestTraDiemCic:
    """tra_diem_cic() gọi GET /api/v1/diem-tin-dung/{soCccd}."""

    @pytest.mark.asyncio
    async def test_tra_ve_diem_khi_thanh_cong(self, client, httpx_mock):
        """CIC trả 200 với diemCic hợp lệ → trả về int."""
        httpx_mock.add_response(
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
            json={
                "soCccd": "012345678901",
                "diemCic": 580,
                "thoiDiemTraCuu": "2026-08-11T10:00:00+07:00",
            },
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua == 580

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_timeout(self, client, httpx_mock):
        """CIC timeout → trả None, không raise."""
        httpx_mock.add_exception(
            httpx.ReadTimeout("timeout"),
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_500(self, client, httpx_mock):
        """CIC trả 500 → trả None, không raise."""
        httpx_mock.add_response(
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
            status_code=500,
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_connection_error(self, client, httpx_mock):
        """CIC không khả dụng → trả None, không raise."""
        httpx_mock.add_exception(
            httpx.ConnectError("connection refused"),
            url="http://localhost:8082/api/v1/diem-tin-dung/012345678901",
        )
        ket_qua = await client.tra_diem_cic("012345678901")
        assert ket_qua is None
