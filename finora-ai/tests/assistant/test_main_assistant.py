"""Test phần khởi động/tắt của trợ lý trong `main.py` mà test của brief Task 15 chưa phủ.

- Lúc tắt phải đóng client HTTP của router LLM (báo cáo Task 12): router là một instance cho cả
  tiến trình, không đóng thì kết nối keep-alive tới nhà cung cấp bị bỏ lửng mỗi lần reload.
- Không được dựng router mới chỉ để đóng nó khi trợ lý chưa từng dùng.
- Logger `httpx` ở mức WARNING: dòng INFO của httpx in URL đầy đủ, có mã hồ sơ người dùng.
"""

import logging
from types import SimpleNamespace

from fastapi.testclient import TestClient

import main
from main import app

from .helpers import make_settings


class RouterGia:
    def __init__(self) -> None:
        self.da_dong = 0

    async def aclose(self) -> None:
        self.da_dong += 1


class GetRouterGia:
    """Thay `get_llm_router` (hàm lru_cache): có `cache_info()` và gọi được."""

    def __init__(self, da_dung: bool) -> None:
        self.router = RouterGia()
        self.da_dung = da_dung
        self.so_lan_goi = 0

    def cache_info(self) -> SimpleNamespace:
        return SimpleNamespace(currsize=1 if self.da_dung else 0)

    def __call__(self) -> RouterGia:
        self.so_lan_goi += 1
        return self.router


def test_tat_service_thi_dong_router_llm_da_dung(monkeypatch):
    get_router = GetRouterGia(da_dung=True)
    monkeypatch.setattr(main, "get_settings", lambda: make_settings(enabled=False))
    monkeypatch.setattr(main, "get_llm_router", get_router)

    with TestClient(app):
        assert get_router.router.da_dong == 0

    assert get_router.router.da_dong == 1


def test_router_chua_dung_thi_khong_dung_moi_chi_de_dong(monkeypatch):
    get_router = GetRouterGia(da_dung=False)
    monkeypatch.setattr(main, "get_settings", lambda: make_settings(enabled=False))
    monkeypatch.setattr(main, "get_llm_router", get_router)

    with TestClient(app):
        pass

    assert get_router.so_lan_goi == 0


def test_dong_router_loi_khong_lam_hong_luc_tat(monkeypatch, caplog):
    class RouterHong(RouterGia):
        async def aclose(self) -> None:
            raise RuntimeError("đã đóng")

    get_router = GetRouterGia(da_dung=True)
    get_router.router = RouterHong()
    monkeypatch.setattr(main, "get_settings", lambda: make_settings(enabled=False))
    monkeypatch.setattr(main, "get_llm_router", get_router)

    with TestClient(app):
        pass

    assert "Không đóng được client HTTP của router LLM" in caplog.text


def test_log_httpx_khong_in_url():
    # Đặt mức riêng của logger, không dựa vào mức gốc: uvicorn chạy thật thì `basicConfig` để gốc ở INFO
    # (dưới pytest gốc đã có handler nên `basicConfig` không làm gì và mức hiệu lực luôn là WARNING).
    assert logging.getLogger("httpx").level == logging.WARNING
