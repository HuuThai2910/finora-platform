"""Chọn bộ công cụ theo vai trò trong token, nơi gửi và công tắc ASSISTANT_TOOLSETS."""

import pytest

from app.services.assistant.principal import Toolset, resolve_toolset

from .helpers import make_principal

TAT_CA = frozenset(Toolset)
B, I, A = Toolset.BORROWER, Toolset.INVESTOR, Toolset.ADMIN


@pytest.mark.parametrize(
    ("roles", "surface", "expected"),
    [
        (("ROLE_BORROWER",), "mobile", {B}),
        (("ROLE_INVESTOR",), "mobile", {I}),
        (("ROLE_BORROWER", "ROLE_INVESTOR"), "mobile", {B, I}),
        (("ROLE_ADMIN",), "web", {A}),
        # Web chỉ dành cho quản trị; mobile không có bộ công cụ quản trị.
        (("ROLE_ADMIN",), "mobile", set()),
        (("ROLE_BORROWER",), "web", set()),
        (("ROLE_INVESTOR", "ROLE_BORROWER"), "web", set()),
        # Token mang nhiều role: mỗi nơi chỉ lấy phần của nơi đó.
        (("ROLE_ADMIN", "ROLE_BORROWER"), "mobile", {B}),
        (("ROLE_ADMIN", "ROLE_BORROWER"), "web", {A}),
        ((), "mobile", set()),
        (("ROLE_BORROWER",), "desktop", set()),
        (("borrower",), "mobile", set()),
    ],
)
def test_chon_bo_cong_cu_theo_role_va_noi_gui(roles, surface, expected):
    assert resolve_toolset(make_principal(*roles), surface, TAT_CA) == frozenset(expected)


def test_giao_voi_cong_tac_bat_tat():
    principal = make_principal("ROLE_BORROWER", "ROLE_INVESTOR")
    assert resolve_toolset(principal, "mobile", frozenset({B})) == frozenset({B})
    assert resolve_toolset(principal, "mobile", frozenset({A})) == frozenset()


def test_tat_het_thi_rong():
    assert resolve_toolset(make_principal("ROLE_ADMIN"), "web", frozenset()) == frozenset()


def test_toolset_la_chuoi_de_ghi_log():
    assert Toolset.BORROWER == "BORROWER"
    assert Toolset("ADMIN") is Toolset.ADMIN
