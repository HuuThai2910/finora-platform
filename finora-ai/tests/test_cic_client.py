"""Test CIC client v14 — trả dict thay vì int."""

import httpx
import pytest

from app.services.cic_client import CicClient

CIC_URL = "http://localhost:8082/api/v1/diem-tin-dung/012345678901?chiTiet=true"


@pytest.fixture
def client():
    return CicClient(base_url="http://localhost:8082")


class TestTraDiemCicV14:
    """tra_diem_cic() trả dict | None thay vì int | None."""

    @pytest.mark.asyncio
    async def test_tra_ve_dict_khi_thanh_cong(self, client, httpx_mock):
        """CIC trả 200 với hoSo → trả dict đầy đủ."""
        httpx_mock.add_response(
            url=CIC_URL,
            json={
                "soCccd": "012345678901",
                "diemCic": 580,
                "thoiDiemTraCuu": "2026-08-14T10:00:00+07:00",
                "hoSo": {
                    "soLanTreHan24Thang": 2,
                    "soThangTuLanTreGanNhat": 6,
                    "soNgayTreDaiNhat": 30,
                    "nhomNoCaoNhat": 1,
                    "tongDuNo": 50000000,
                    "duNoTheTinDung": 5000000,
                    "hanMucThe": 20000000,
                    "soLanTraCuu6Thang": 1,
                    "soHopDongDangCo": 3,
                    "soThangQuanHe": 48,
                },
            },
        )
        result = await client.tra_diem_cic("012345678901")
        assert isinstance(result, dict)
        assert result["cic_score"] == 580
        assert result["so_lan_tre_han"] == 2
        assert result["thang_tu_tre_gan_nhat"] == 6
        assert result["tong_du_no"] == 50000000
        assert result["du_no_the_tin_dung"] == 5000000
        assert result["ty_le_su_dung_the"] == pytest.approx(25.0)
        assert result["so_lan_tra_cuu"] == 1
        assert result["so_hop_dong_dang_co"] == 3
        assert result["so_thang_quan_he"] == 48
        assert result["nhom_no_cao_nhat"] == 1

    @pytest.mark.asyncio
    async def test_ty_le_su_dung_the_none_khi_han_muc_0(self, client, httpx_mock):
        """hanMucThe = 0 → ty_le_su_dung_the = None (tránh chia cho 0)."""
        httpx_mock.add_response(
            url=CIC_URL,
            json={
                "soCccd": "012345678901",
                "diemCic": 400,
                "hoSo": {
                    "soLanTreHan24Thang": 0, "soThangTuLanTreGanNhat": -1,
                    "soNgayTreDaiNhat": 0, "nhomNoCaoNhat": None,
                    "tongDuNo": 0, "duNoTheTinDung": 0, "hanMucThe": 0,
                    "soLanTraCuu6Thang": 0, "soHopDongDangCo": 0,
                    "soThangQuanHe": None,
                },
            },
        )
        result = await client.tra_diem_cic("012345678901")
        assert result["ty_le_su_dung_the"] is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_timeout(self, client, httpx_mock):
        """CIC timeout → trả None, không raise."""
        httpx_mock.add_exception(httpx.ReadTimeout("timeout"), url=CIC_URL)
        result = await client.tra_diem_cic("012345678901")
        assert result is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_500(self, client, httpx_mock):
        """CIC trả 500 → trả None."""
        httpx_mock.add_response(url=CIC_URL, status_code=500)
        result = await client.tra_diem_cic("012345678901")
        assert result is None

    @pytest.mark.asyncio
    async def test_tra_ve_none_khi_connection_error(self, client, httpx_mock):
        """CIC không khả dụng → trả None."""
        httpx_mock.add_exception(httpx.ConnectError("connection refused"), url=CIC_URL)
        result = await client.tra_diem_cic("012345678901")
        assert result is None

    @pytest.mark.asyncio
    async def test_hoSo_none_van_tra_cic_score(self, client, httpx_mock):
        """Response không có hoSo (cic-service phiên bản cũ) → vẫn trả cic_score."""
        httpx_mock.add_response(
            url=CIC_URL,
            json={
                "soCccd": "012345678901",
                "diemCic": 620,
                "thoiDiemTraCuu": "2026-08-14T10:00:00+07:00",
            },
        )
        result = await client.tra_diem_cic("012345678901")
        assert result["cic_score"] == 620
        assert result["so_lan_tre_han"] is None
