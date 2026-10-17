"""Kiểm nội dung kho kiến thức của Trợ lý FINORA (AI-CHAT-001, Task 8).

Kho kiến thức là YAML viết tay nên lỗi hay gặp nằm ở nội dung chứ không ở code: trùng mã, gắn nhãn
LUAT cho một câu chính sách, dẫn một điều luật không có trong sổ pháp lý, link lệch sổ, hoặc một đoạn
người vay đọc được lại nhắc tới SHAP. Các test dưới đây chặn những lỗi đó trước khi trợ lý nói sai
với người dùng. Cơ chế nạp file, BM25 và dựng trích dẫn đã có test riêng ở Task 7; ở đây chỉ kiểm
dữ liệu thật trong `knowledge/`.
"""

from __future__ import annotations

import re
from collections import Counter
from pathlib import Path

import pytest

from app.services.assistant.knowledge.bm25 import Bm25Index
from app.services.assistant.knowledge.store import KnowledgeChunk, load_chunks
from app.services.assistant.legal import (
    LegalSource,
    citations_for_chunk,
    load_legal_sources,
    parse_ref,
)

# tests/assistant/<file> -> parents[2] là thư mục finora-ai. Sổ pháp lý nằm ở finora-platform/docs,
# tức ../docs/LEGAL-COMPLIANCE.md tính từ finora-ai.
FINORA_AI = Path(__file__).resolve().parents[2]
LEGAL_DOC = FINORA_AI.parent / "docs" / "LEGAL-COMPLIANCE.md"

KINDS = {"LUAT", "CHINH_SACH", "CHUA_XAC_MINH", "HUONG_DAN"}
ROLES = {"BORROWER", "INVESTOR", "ADMIN", "ALL"}
STATUSES = {"CURRENT", "NEEDS_LEGAL_REVIEW", "NEEDS_PRIMARY_SOURCE"}
NGAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")
MA_DOAN = re.compile(r"^[A-Z]+(?:-[A-Z0-9]+)+-\d{2}$")
# Phần sau dấu ":" của legal_refs, ví dụ "Điều 11 khoản 1 điểm c".
DIEU = re.compile(r"^Điều (\d+)(?: khoản \d+)?(?: điểm [a-zđ])?$")

# Số hiệu văn bản trong thân đoạn sẽ bị LLM chép lại; guard (Task 13) coi "Điều …", "Nghị định …",
# "Thông tư …", "Bộ luật", "Luật …/20xx" không có trong căn cứ của lượt là trích dẫn bịa và thay câu
# trả lời bằng câu mẫu. Vì vậy căn cứ chỉ đi qua legal_refs để code gắn, thân đoạn chỉ nói nội dung.
SO_HIEU_VAN_BAN = re.compile(
    r"\bĐiều\s+\d+|Nghị\s+định|Thông\s+tư|Bộ\s+luật|\bLuật\b[^.\n]{0,60}/20\d\d",
    re.IGNORECASE,
)

# Orchestrator ghép tối đa 4 đoạn vào prompt mà cả prompt chỉ nên quanh 2.000 token (spec mục 4, 7.4);
# đoạn dài hơn thế này thì nên tách thành hai đoạn.
DO_DAI_TOI_DA = 900

# Giải thích SHAP chỉ dành cho thẩm định viên (LEGAL-DISCLOSURE-02).
TU_KHOA_SHAP = re.compile(r"SHAP|log-odds|int_rate|dien_giai", re.IGNORECASE)


@pytest.fixture(scope="module")
def chunks() -> list[KnowledgeChunk]:
    return load_chunks()


@pytest.fixture(scope="module")
def sources() -> dict[str, LegalSource]:
    return load_legal_sources()


@pytest.fixture(scope="module")
def so_phap_ly() -> str:
    if not LEGAL_DOC.is_file():
        # Chỉ xảy ra khi chạy test ngoài repo finora-platform (ví dụ trong image chỉ có finora-ai).
        pytest.skip(f"Không thấy sổ pháp lý tại {LEGAL_DOC}")
    return LEGAL_DOC.read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def index(chunks: list[KnowledgeChunk]) -> Bm25Index:
    return Bm25Index(chunks)


def bang_nguon(so_phap_ly: str) -> dict[str, str]:
    """Dòng bảng mục 1 "Nguồn chính thức đang áp dụng" của sổ pháp lý, theo mã nguồn."""
    muc_1 = so_phap_ly.split("## 1.", 1)[1].split("## 2.", 1)[0]
    dong: dict[str, str] = {}
    for line in muc_1.splitlines():
        khop = re.match(r"^\| `([A-Z0-9-]+)` \|", line)
        if khop:
            dong[khop.group(1)] = line
    return dong


def doc_duoc(chunk: KnowledgeChunk, vai_tro: str) -> bool:
    return vai_tro in chunk.roles or "ALL" in chunk.roles


def theo_ma(chunks: list[KnowledgeChunk]) -> dict[str, KnowledgeChunk]:
    return {chunk.id: chunk for chunk in chunks}


class TestKhuonDoan:
    def test_tong_so_doan_trong_khoang_55_den_70(self, chunks):
        assert 55 <= len(chunks) <= 70, f"Đang có {len(chunks)} đoạn"

    def test_ma_doan_duy_nhat_va_dung_khuon(self, chunks):
        trung = [ma for ma, so in Counter(c.id for c in chunks).items() if so > 1]
        assert trung == []
        sai_khuon = [c.id for c in chunks if not MA_DOAN.match(c.id)]
        assert sai_khuon == []

    def test_truong_bat_buoc_hop_le(self, chunks):
        for chunk in chunks:
            assert chunk.kind in KINDS, chunk.id
            assert chunk.roles and chunk.roles <= ROLES, chunk.id
            # "ALL" đã gồm mọi vai trò; trộn với vai trò khác là dấu hiệu viết nhầm.
            assert "ALL" not in chunk.roles or chunk.roles == {"ALL"}, chunk.id
            assert chunk.title.strip(), chunk.id
            assert chunk.source.strip() and chunk.version.strip(), chunk.id
            assert NGAY.match(chunk.checked_at), chunk.id
            assert len(chunk.keywords) >= 3, chunk.id

    def test_than_doan_khong_qua_dai(self, chunks):
        qua_dai = {c.id: len(c.body) for c in chunks if not 120 <= len(c.body) <= DO_DAI_TOI_DA}
        assert qua_dai == {}

    @pytest.mark.parametrize("vai_tro", ["BORROWER", "INVESTOR", "ADMIN"])
    def test_moi_vai_tro_doc_duoc_it_nhat_12_doan(self, chunks, vai_tro):
        assert sum(1 for c in chunks if doc_duoc(c, vai_tro)) >= 12


class TestNguonPhapLy:
    def test_khop_hai_chieu_voi_bang_muc_1(self, sources, so_phap_ly):
        # Mọi mã trong YAML có trong sổ, và mọi văn bản trong bảng mục 1 đều có trong YAML.
        assert set(sources) == set(bang_nguon(so_phap_ly))

    def test_link_chep_nguyen_van_tu_so_phap_ly(self, sources, so_phap_ly):
        dong = bang_nguon(so_phap_ly)
        for ma, nguon in sources.items():
            assert nguon.url.startswith("https://"), ma
            assert f"({nguon.url})" in dong[ma], f"{ma}: link không có trong dòng bảng của sổ"

    def test_trang_thai_va_ngay_kiem(self, sources):
        for ma, nguon in sources.items():
            assert nguon.status in STATUSES, ma
            assert NGAY.match(nguon.checked_at), ma
            assert nguon.title.strip(), ma
        # Sổ ghi phạm vi áp dụng văn bản hợp nhất về cho vay "cần legal review".
        assert sources["CIRCULAR-LENDING-CONSOLIDATED-2026"].status == "NEEDS_LEGAL_REVIEW"
        assert sources["LAW-P2P-2025"].checked_at == "2026-10-10"


class TestNhanVaCanCu:
    def test_chi_doan_luat_moi_co_can_cu(self, chunks):
        for chunk in chunks:
            if chunk.kind == "LUAT":
                assert chunk.legal_refs, f"{chunk.id}: đoạn LUAT phải có legal_refs"
            else:
                assert not chunk.legal_refs, f"{chunk.id}: chỉ đoạn LUAT được mang căn cứ luật"

    def test_can_cu_tro_toi_dieu_co_trong_so_phap_ly(self, chunks, sources, so_phap_ly):
        dong = bang_nguon(so_phap_ly)
        for chunk in chunks:
            for ref in chunk.legal_refs:
                ma, dieu = parse_ref(ref)
                assert ma in sources, f"{chunk.id}: {ma} không có trong legal_sources.yaml"
                khop = DIEU.match(dieu)
                assert khop, f"{chunk.id}: '{dieu}' sai khuôn 'Điều X khoản Y điểm z'"
                assert re.search(rf"\b{khop.group(1)}\b", dong[ma]), (
                    f"{chunk.id}: Điều {khop.group(1)} không có trong dòng {ma} của sổ pháp lý"
                )

    def test_trich_dan_luat_co_link_chinh_thuc(self, chunks, sources):
        link_hop_le = {nguon.url for nguon in sources.values()}
        for chunk in chunks:
            if chunk.kind != "LUAT":
                continue
            luat = [c for c in citations_for_chunk(chunk, sources) if c.kind == "LUAT"]
            assert len(luat) == len(chunk.legal_refs), chunk.id
            assert all(c.url in link_hop_le for c in luat), chunk.id

    def test_than_doan_khong_chua_so_hieu_van_ban(self, chunks):
        vi_pham = {
            c.id: m.group(0)
            for c in chunks
            if (m := SO_HIEU_VAN_BAN.search(f"{c.title}\n{c.body}"))
        }
        assert vi_pham == {}

    def test_co_doan_chua_xac_minh_ve_han_muc_100_400_trieu(self, chunks):
        assert any(
            c.kind == "CHUA_XAC_MINH" and "100 triệu" in c.body and "400 triệu" in c.body
            for c in chunks
        )

    @pytest.mark.parametrize(
        ("ma", "kind", "cum_tu"),
        [
            ("NV-TAT-TOAN-01", "CHINH_SACH", ["Vietcombank", "0,5%", "1%", "100.000 đ", "15 phút"]),
            ("NV-HANG-GIA-01", "CHINH_SACH", ["0,5", "1,0", "20%/năm"]),
            ("NV-DIEU-KIEN-01", "CHINH_SACH", ["3 tháng", "18", "65"]),
            ("NV-LAI-CUOI-01", "CHINH_SACH", ["7 ngày", "không được coi là đồng ý"]),
            ("NV-DUYET-GIAI-NGAN-01", "CHINH_SACH", ["chưa phải giải ngân"]),
            ("QT-NGUONG-DUYET-01", "CHINH_SACH", ["75", "65", "60%"]),
            ("NDT-PHI-CHO-01", "CHINH_SACH", ["5%"]),
            ("NDT-CUA-SO-01", "CHINH_SACH", ["14 ngày"]),
            ("NDT-MENH-GIA-01", "CHINH_SACH", ["1.000.000 đ"]),
            ("CHUNG-PIN-01", "HUONG_DAN", ["6 chữ số", "5 lần", "15 phút"]),
            ("CHUNG-NAP-TIEN-01", "HUONG_DAN", ["10.000 đ", "100.000.000 đ"]),
            ("CHUNG-RUT-TIEN-01", "HUONG_DAN", ["chưa hỗ trợ"]),
            ("PL-THOI-HAN-01", "LUAT", ["02 năm", "24 tháng"]),
            ("PL-TRAN-LAI-01", "LUAT", ["20%/năm"]),
            ("PL-HAN-MUC-01", "CHUA_XAC_MINH", ["100 triệu", "400 triệu", "chưa"]),
        ],
    )
    def test_doan_then_chot_dung_nhan_va_so_lieu(self, chunks, ma, kind, cum_tu):
        chunk = theo_ma(chunks)[ma]
        assert chunk.kind == kind
        for cum in cum_tu:
            assert cum in chunk.body, f"{ma} thiếu '{cum}'"

    def test_khong_lo_shap_cho_nguoi_vay_va_nha_dau_tu(self, chunks):
        lo = [
            c.id
            for c in chunks
            if (doc_duoc(c, "BORROWER") or doc_duoc(c, "INVESTOR"))
            and TU_KHOA_SHAP.search(" ".join([c.title, c.body, *c.keywords]))
        ]
        assert lo == []


class TestTimKiem:
    @pytest.mark.parametrize(
        ("cau_hoi", "vai_tro", "ma_mong_doi"),
        [
            ("Đã duyệt sao chưa nhận được tiền?", "BORROWER", "NV-DUYET-GIAI-NGAN-01"),
            ("tat toan truoc han mat phi bao nhieu", "BORROWER", "NV-TAT-TOAN-01"),
            ("Vì sao lãi suất cuối khác lãi lúc nộp hồ sơ?", "BORROWER", "NV-LAI-CUOI-01"),
            ("nhap sai ma PIN bi khoa bao lau", "BORROWER", "CHUNG-PIN-01"),
            ("Sao vay tối đa chỉ 24 tháng?", "BORROWER", "PL-THOI-HAN-01"),
            ("Hạn mức 100 triệu là luật à?", "BORROWER", "PL-HAN-MUC-01"),
            ("Sao lệnh mua của tôi bị hủy vì tự khớp?", "INVESTOR", "NDT-TU-KHOP-01"),
            ("phi ban Note tren cho la bao nhieu", "INVESTOR", "NDT-PHI-CHO-01"),
            ("Commitment khác Note thế nào?", "INVESTOR", "NDT-COMMITMENT-NOTE-01"),
            ("Auto-Invest bỏ qua khoản vay vì không đủ tiền", "INVESTOR", "NDT-AUTO-BO-QUA-01"),
            ("Vì sao hồ sơ 70 điểm vẫn phải duyệt tay?", "ADMIN", "QT-NGUONG-DUYET-01"),
            ("ty le no xau NPL tinh the nao", "ADMIN", "QT-KPI-NPL-01"),
        ],
    )
    def test_bm25_tra_dung_doan_trong_top_3(self, index, cau_hoi, vai_tro, ma_mong_doi):
        ket_qua = index.search(cau_hoi, toolsets=frozenset({vai_tro}), limit=3)
        ma = [chunk.id for chunk, _ in ket_qua]
        assert ma_mong_doi in ma, f"'{cau_hoi}' trả {ma}"

    def test_vai_tro_khong_thay_doan_cua_vai_tro_khac(self, index):
        # Người vay hỏi đúng chữ của đoạn quản trị vẫn không được nhận đoạn đó.
        ket_qua = index.search("ngưỡng tự duyệt 75 điểm", toolsets=frozenset({"BORROWER"}), limit=4)
        assert all(doc_duoc(chunk, "BORROWER") for chunk, _ in ket_qua)
