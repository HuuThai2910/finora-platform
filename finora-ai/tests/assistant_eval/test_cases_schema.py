"""Kiểm bộ câu đánh giá và fixture trước khi tốn hạn mức LLM thật (AI-CHAT-001 Task 19).

Lỗi hay gặp khi viết tay 90 câu: sai tên tool, sai khóa nút, màn không tồn tại, fixture thiếu route
nên tool nhận 599 và câu trả lời thành "chưa lấy được dữ liệu" mà không ai biết vì sao. Các test dưới
đây chặn những lỗi đó bằng chính registry, bảng deeplinks và allowlist route thật của trợ lý.
"""

import json
import re
from collections import Counter

import pytest

from app.services.assistant.deeplinks import allowed_action_keys

from .harness import (
    FIXTURE_DIR,
    MAN_HO_TRO,
    NHAN_BAT_BUOC,
    NHAN_HOP_LE,
    NHOM,
    SURFACE_CUA_VAI_TRO,
    TIEN_TO_ID,
    TOOLSET_CUA_VAI_TRO,
    dung_registry,
    load_cases,
    load_fixture,
    pii_trong_cau_hoi,
    quet_pii,
    tat_ca_tool,
    ten_tool_cua_vai_tro,
)

CASES = load_cases()
REGISTRY = dung_registry()


def test_du_90_ca_moi_vai_tro_30_va_id_khong_trung():
    assert len(CASES) == 90
    assert Counter(c.role for c in CASES) == {"BORROWER": 30, "INVESTOR": 30, "ADMIN": 30}
    ids = [c.id for c in CASES]
    assert len(ids) == len(set(ids))
    for case in CASES:
        assert case.id.startswith(TIEN_TO_ID[case.role]), case.id
        assert re.fullmatch(r"(NV|NDT|QT)-(TT|DM|TC|CS|TU|BM|NP)-\d{2}", case.id), case.id


def test_moi_vai_tro_du_7_nhom_va_surface_dung():
    for role in ("BORROWER", "INVESTOR", "ADMIN"):
        dem = Counter(c.group for c in CASES if c.role == role)
        assert set(dem) == set(NHOM), role
        assert all(so >= 3 for so in dem.values()), (role, dem)
    for case in CASES:
        assert case.surface == SURFACE_CUA_VAI_TRO[case.role], case.id


def test_cau_dac_biet_cua_spec_du_moi_vai_tro():
    """Spec mục 15: câu bẫy số điều, hạn mức 100/400 triệu, xin thao tác/khuyến nghị/PIN, dò bảo mật."""
    for (role, group), can in NHAN_BAT_BUOC.items():
        co = {t for c in CASES if c.role == role and c.group == group for t in c.tags}
        assert can <= co, (role, group, can - co)
    for case in CASES:
        assert set(case.tags) <= NHAN_HOP_LE, case.id


def test_ten_tool_dung_registry_va_vai_tro():
    tat_ca = {spec.name for spec in tat_ca_tool()}
    for case in CASES:
        cua_vai_tro = ten_tool_cua_vai_tro(case.role)
        mong_doi = set(case.expect.tools) | set(case.expect.tools_optional)
        assert mong_doi <= tat_ca, (case.id, mong_doi - tat_ca)
        assert mong_doi <= cua_vai_tro, (case.id, "tool của vai trò khác", mong_doi - cua_vai_tro)
        assert not set(case.expect.tools) & set(case.expect.tools_optional), case.id


def test_goi_y_ton_tai_dung_vai_tro_va_dung_tool():
    for case in CASES:
        if case.suggestion_id is None:
            continue
        goi_y = REGISTRY.suggestion(case.suggestion_id)
        assert goi_y is not None, case.id
        assert goi_y.toolset is TOOLSET_CUA_VAI_TRO[case.role], case.id
        assert case.expect.tools == [goi_y.tool], case.id
        man = case.context.screen if case.context else None
        assert not goi_y.screens or man in goi_y.screens, (case.id, man)
        thieu = set(goi_y.params_from_context.values()) - set(case.context.params if case.context else {})
        assert not thieu, (case.id, thieu)
    assert sum(1 for c in CASES if c.suggestion_id) >= 15  # đủ câu đo riêng đường câu mẫu


def test_man_hinh_va_tham_so_hop_le():
    for case in CASES:
        if case.context is None or case.context.screen is None:
            continue
        man = MAN_HO_TRO[case.role]
        assert case.context.screen in man, case.id
        assert set(case.context.params) <= man[case.context.screen], case.id
        assert all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}", v) for v in case.context.params.values()), case.id


def test_khoa_nut_va_loai_can_cu_hop_le():
    for case in CASES:
        assert set(case.expect.expect_actions) <= set(allowed_action_keys(case.surface)), case.id
    # Câu chính sách/pháp lý phải đo trích dẫn; câu bẫy số điều phải cấm đúng số điều bịa.
    for role in ("BORROWER", "INVESTOR", "ADMIN"):
        co_can_cu = [c for c in CASES
                     if c.role == role and c.group == "CHINH_SACH_PHAP_LY" and c.expect.expect_citation_kinds]
        assert len(co_can_cu) >= 3, role
    for case in CASES:
        if "bay_so_dieu" in case.tags:
            dieu = re.search(r"Điều \d+", case.message)
            assert dieu and dieu.group(0) in case.expect.must_not_contain, case.id
        if "han_muc" in case.tags:
            assert case.expect.expect_citation_kinds == ["CHUA_XAC_MINH"], case.id
        if case.group in ("TU_CHOI", "NGOAI_PHAM_VI"):
            assert case.expect.expect_refusal, case.id


@pytest.mark.parametrize("ten", sorted({c.fixture for c in CASES}))
def test_fixture_ton_tai_dung_vai_tro_va_la_du_lieu_gia(ten):
    assert (FIXTURE_DIR / f"{ten}.json").exists()
    fixture = load_fixture(ten)
    assert fixture.du_lieu_gia is True
    roles = {c.role for c in CASES if c.fixture == ten}
    assert roles == {fixture.role}
    khoa = [(r.method, r.service, r.path, tuple(sorted(r.query.items())), json.dumps(r.body_match, sort_keys=True))
            for r in fixture.routes]
    assert len(khoa) == len(set(khoa)), "route trùng"


@pytest.mark.parametrize("ten", sorted({c.fixture for c in CASES}))
def test_route_fixture_nam_trong_allowlist_cua_vai_tro(ten):
    """Fixture chỉ khai route mà tool của vai trò được phép gọi (allowlist relay của Task 6)."""
    fixture = load_fixture(ten)
    rules = REGISTRY.routes_for(frozenset({TOOLSET_CUA_VAI_TRO[fixture.role]}))
    for route in fixture.routes:
        assert any(
            rule.method == route.method and rule.service.value == route.service
            and re.fullmatch(rule.pattern, route.path) for rule in rules
        ), (ten, route.method, route.service, route.path)


def test_tool_mong_doi_co_du_route_trong_fixture():
    """Mỗi route của tool bắt buộc phải có ít nhất một dòng fixture khớp, nếu không tool nhận 599."""
    specs = {spec.name: spec for spec in tat_ca_tool()}
    for case in CASES:
        fixture = load_fixture(case.fixture)
        for ten in case.expect.tools:
            for rule in specs[ten].routes:
                assert any(
                    r.method == rule.method and r.service == rule.service.value and re.fullmatch(rule.pattern, r.path)
                    for r in fixture.routes
                ), (case.id, ten, rule.pattern)


def test_khong_co_pii_that():
    """Email chỉ dùng miền .invalid; mọi chuỗi giống PII trong fixture phải được khai là canary giả."""
    for ten in sorted({c.fixture for c in CASES}):
        fixture = load_fixture(ten)
        van_ban = json.dumps([r.body for r in fixture.routes], ensure_ascii=False)
        for email in re.findall(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", van_ban):
            assert email.endswith(".invalid"), (ten, email)
        for loai in quet_pii(van_ban):
            assert any(loai in quet_pii(c) for c in fixture.pii_canaries), (ten, loai)
    for case in CASES:
        for gia_tri in pii_trong_cau_hoi(case.message):
            assert "@" not in gia_tri or gia_tri.endswith(".invalid"), case.id
            assert gia_tri in case.expect.must_not_contain, (case.id, gia_tri)
