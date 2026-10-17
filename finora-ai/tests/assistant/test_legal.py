"""Test nguồn pháp lý và trích dẫn của trợ lý (AI-CHAT-001, Task 7).

Trích dẫn do code gắn, không do LLM viết. Các test ở đây giữ ba bất biến:
- trích dẫn LUAT luôn có link văn bản chính thức;
- căn cứ trỏ tới mã chưa khai báo thì lỗi ngay (không có trích dẫn trỏ vào hư không);
- chính sách FINORA và thông tin chưa xác minh không bao giờ mang nhãn LUAT.
"""

from textwrap import dedent

import pytest

from app.services.assistant.glossary import can_cu_ly_do
from app.services.assistant.knowledge.store import KnowledgeChunk, load_chunks
from app.services.assistant.legal import (
    LegalSource,
    citation_for,
    citations_for_chunk,
    load_legal_sources,
    parse_ref,
)

NGUON = {
    "LAW-P2P-2025": LegalSource(
        id="LAW-P2P-2025",
        title="Nghị định 94/2025/NĐ-CP",
        url="https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/5/94-ndcp.signed.pdf",
        status="CURRENT",
        checked_at="2026-10-10",
    ),
    "LAW-CIVIL-2015": LegalSource(
        id="LAW-CIVIL-2015",
        title="Bộ luật Dân sự 91/2015/QH13",
        url="https://vanban.chinhphu.vn/default.aspx?docid=183188&pageid=27160",
        status="CURRENT",
        checked_at="2026-10-03",
    ),
}


def _doan(kind: str, legal_refs: tuple[str, ...] = (), **ghi_de) -> KnowledgeChunk:
    truong = dict(
        id="PL-01",
        title="Kỳ hạn vay tối đa",
        roles=frozenset({"ALL"}),
        kind=kind,
        body="Hợp đồng vay không quá 02 năm.",
        legal_refs=legal_refs,
        source="docs/LEGAL-COMPLIANCE.md",
        version="CREDIT_POLICY_V1",
        checked_at="2026-10-15",
        keywords=(),
    )
    truong.update(ghi_de)
    return KnowledgeChunk(**truong)


def test_doc_file_nguon_phap_ly(tmp_path):
    file = tmp_path / "legal_sources.yaml"
    file.write_text(
        dedent(
            """\
            - id: LAW-P2P-2025
              title: Nghị định 94/2025/NĐ-CP
              url: https://datafiles.chinhphu.vn/cpp/files/vbpq/2025/5/94-ndcp.signed.pdf
              status: CURRENT
              checked_at: 2026-10-10
            """
        ),
        encoding="utf-8",
    )

    assert load_legal_sources(file) == {"LAW-P2P-2025": NGUON["LAW-P2P-2025"]}


@pytest.mark.parametrize(
    "ghi_de, thong_bao",
    [
        ({"url": "http://vanban.chinhphu.vn/x"}, "https"),
        ({"status": "OK"}, "status phải thuộc"),
        ({"id": "law-p2p"}, "mã nguồn chỉ gồm"),
        ({"title": None}, "phải là chuỗi"),
        ({"ghi_chu": "x"}, "trường lạ"),
    ],
)
def test_nguon_phap_ly_sai_khuon(tmp_path, ghi_de, thong_bao):
    truong = {
        "id": "LAW-P2P-2025",
        "title": "Nghị định 94/2025/NĐ-CP",
        "url": "https://datafiles.chinhphu.vn/a.pdf",
        "status": "CURRENT",
        "checked_at": "2026-10-10",
    }
    truong.update(ghi_de)
    dong = [f"  {k}: {v}" for k, v in truong.items() if v is not None]
    dong[0] = "- " + dong[0].lstrip()
    file = tmp_path / "legal_sources.yaml"
    file.write_text("\n".join(dong) + "\n", encoding="utf-8")

    with pytest.raises(ValueError, match=thong_bao):
        load_legal_sources(file)


def test_trung_ma_nguon_bi_tu_choi(tmp_path):
    mot = (
        "- id: LAW-P2P-2025\n  title: NĐ 94\n  url: https://a.vn/x.pdf\n"
        "  status: CURRENT\n  checked_at: 2026-10-10\n"
    )
    file = tmp_path / "legal_sources.yaml"
    file.write_text(mot + mot, encoding="utf-8")

    with pytest.raises(ValueError, match="trùng mã nguồn pháp lý"):
        load_legal_sources(file)


@pytest.mark.parametrize(
    "ref, mong_doi",
    [
        ("LAW-P2P-2025:Điều 11 khoản 1 điểm c", ("LAW-P2P-2025", "Điều 11 khoản 1 điểm c")),
        ("  LAW-P2P-2025 :  Điều 22   khoản 3 ", ("LAW-P2P-2025", "Điều 22 khoản 3")),
        ("LAW-DATA-2025", ("LAW-DATA-2025", "")),
    ],
)
def test_parse_ref(ref, mong_doi):
    assert parse_ref(ref) == mong_doi


@pytest.mark.parametrize("ref", ["", "   ", ":Điều 11", "law-p2p-2025:Điều 1", "Điều 11 NĐ 94"])
def test_parse_ref_sai_dang(ref):
    with pytest.raises(ValueError):
        parse_ref(ref)


def test_citation_for_luat_co_link_va_ngay_kiem():
    citation = citation_for("LAW-P2P-2025:Điều 11 khoản 1 điểm c", NGUON)

    assert citation.kind == "LUAT"
    assert citation.id == "LAW-P2P-2025:Điều 11 khoản 1 điểm c"
    assert citation.label == "Nghị định 94/2025/NĐ-CP, Điều 11 khoản 1 điểm c"
    assert citation.url == NGUON["LAW-P2P-2025"].url
    # JSON gửi app là camelCase theo hợp đồng API.
    assert citation.model_dump(by_alias=True)["checkedAt"] == "2026-10-10"


def test_citation_for_khong_co_dieu_khoan_chi_ghi_ten_van_ban():
    citation = citation_for("LAW-CIVIL-2015", NGUON)

    assert citation.id == "LAW-CIVIL-2015"
    assert citation.label == "Bộ luật Dân sự 91/2015/QH13"


def test_citation_for_ma_chua_khai_bao():
    with pytest.raises(ValueError, match="chưa có trong legal_sources.yaml"):
        citation_for("LAW-AI-2026:Điều 5", NGUON)


def test_doan_luat_chi_sinh_trich_dan_luat_va_bo_trung():
    doan = _doan(
        "LUAT",
        ("LAW-P2P-2025:Điều 11 khoản 1 điểm c", "LAW-P2P-2025: Điều 11  khoản 1 điểm c"),
    )

    citations = citations_for_chunk(doan, NGUON)

    assert [(c.kind, c.label) for c in citations] == [
        ("LUAT", "Nghị định 94/2025/NĐ-CP, Điều 11 khoản 1 điểm c")
    ]


def test_doan_chinh_sach_co_nhan_chinh_sach_roi_moi_toi_luat():
    doan = _doan(
        "CHINH_SACH",
        ("LAW-CIVIL-2015:Điều 468",),
        id="CS-LAI-SUAT-01",
        title="Lãi suất tối đa của sản phẩm",
    )

    chinh_sach, luat = citations_for_chunk(doan, NGUON)

    assert chinh_sach.kind == "CHINH_SACH"
    assert chinh_sach.id == "CS-LAI-SUAT-01"
    assert chinh_sach.label == "Chính sách FINORA: Lãi suất tối đa của sản phẩm (CREDIT_POLICY_V1)"
    assert chinh_sach.url is None
    assert luat.kind == "LUAT"
    assert luat.label == "Bộ luật Dân sự 91/2015/QH13, Điều 468"


def test_doan_chua_xac_minh_khong_mang_nhan_luat():
    doan = _doan("CHUA_XAC_MINH", id="CXM-HAN-MUC-01", title="Hạn mức dư nợ 100/400 triệu")

    [citation] = citations_for_chunk(doan, NGUON)

    assert citation.kind == "CHUA_XAC_MINH"
    assert citation.url is None
    assert citation.label == "Chưa xác minh nguồn gốc: Hạn mức dư nợ 100/400 triệu"


def test_doan_huong_dan_khong_co_can_cu():
    assert citations_for_chunk(_doan("HUONG_DAN"), NGUON) == []


def test_kho_mac_dinh_moi_can_cu_deu_co_nguon():
    nguon = load_legal_sources()
    for doan in load_chunks():
        for citation in citations_for_chunk(doan, nguon):
            if citation.kind == "LUAT":
                assert citation.url and citation.url.startswith("https://")


def test_can_cu_cua_glossary_deu_co_trong_nguon_mac_dinh():
    nguon = load_legal_sources()
    for ma in ("INTEREST_RATE_EXCEEDS_LEGAL_LIMIT", "TERM_EXCEEDS_LEGAL_LIMIT"):
        for ref in can_cu_ly_do(ma):
            assert citation_for(ref, nguon).kind == "LUAT"
