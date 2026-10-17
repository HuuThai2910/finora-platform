"""Nguồn pháp lý và trích dẫn ("Căn cứ") của Trợ lý FINORA.

Vì sao trích dẫn do code gắn
----------------------------
LLM rất hay bịa số điều luật nghe hợp lý. Theo `LAW-CONSUMER-2023` (không gây nhầm lẫn) và
`LAW-P2P-2025` (thông tin phải chính xác), trợ lý chỉ được nêu căn cứ có trong
`knowledge/legal_sources.yaml` — bản chép mã/tên/link/ngày kiểm/trạng thái từ bảng mục 1 của
`docs/LEGAL-COMPLIANCE.md`. Code dựng `Citation` từ `legal_refs` của đoạn kiến thức đã dùng;
LLM được dặn không viết số điều, và guard (Task 13) chặn câu nêu văn bản ngoài danh sách này.

Dẫn luật kiểu A (quyết định ngày 2026-10-15): tóm tắt + số điều/khoản + link văn bản chính
thức; không trích nguyên văn.

Ba nhãn trích dẫn tách bạch cho người đọc:
- `LUAT`: có văn bản chính thức, luôn kèm `url`.
- `CHINH_SACH`: chính sách sản phẩm của FINORA (không phải luật), không có `url`.
- `CHUA_XAC_MINH`: thông tin ngành chưa đối chiếu được bản gốc (ví dụ `LEGAL-OPEN-02`).
Đoạn `HUONG_DAN` (cách dùng app) không sinh căn cứ riêng — hướng dẫn không phải căn cứ.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

from app.schemas.assistant import Citation
from app.services.assistant.knowledge.store import (
    MA_HOP_LE,
    KnowledgeChunk,
    chuoi_bat_buoc,
    doc_yaml_danh_sach,
    ngay_iso,
)

FILE_NGUON_PHAP_LY = Path(__file__).resolve().parent / "knowledge" / "legal_sources.yaml"

# Trạng thái theo cách LEGAL-COMPLIANCE.md đang dùng.
TRANG_THAI_HOP_LE = frozenset({"CURRENT", "NEEDS_LEGAL_REVIEW", "NEEDS_PRIMARY_SOURCE", "SUPERSEDED"})

_TRUONG_NGUON = frozenset({"id", "title", "url", "status", "checked_at"})


@dataclass(frozen=True)
class LegalSource:
    """Một văn bản pháp luật được phép trích dẫn."""

    id: str  # mã trong LEGAL-COMPLIANCE.md, ví dụ LAW-P2P-2025
    title: str  # tên ngắn hiện trên dòng "Căn cứ", ví dụ "Nghị định 94/2025/NĐ-CP"
    url: str  # link văn bản chính thức (https)
    status: str  # CURRENT | NEEDS_LEGAL_REVIEW | NEEDS_PRIMARY_SOURCE | SUPERSEDED
    checked_at: str  # YYYY-MM-DD


def load_legal_sources(path: Path | None = None) -> dict[str, LegalSource]:
    """Nạp danh sách nguồn pháp lý (mặc định `knowledge/legal_sources.yaml`) thành dict theo mã.

    Trùng mã, link không phải https, trạng thái lạ -> ValueError kèm tên file.
    """
    file = Path(path) if path is not None else FILE_NGUON_PHAP_LY
    ket_qua: dict[str, LegalSource] = {}
    for raw in doc_yaml_danh_sach(file):
        noi = f"{file.name} / {raw.get('id', '<thiếu id>')}"
        thua = set(raw) - _TRUONG_NGUON
        if thua:
            raise ValueError(f"{noi}: trường lạ {sorted(thua)}")
        ma = chuoi_bat_buoc(raw, "id", noi)
        if not MA_HOP_LE.fullmatch(ma):
            raise ValueError(f"{noi}: mã nguồn chỉ gồm chữ hoa, số và dấu '-'")
        url = chuoi_bat_buoc(raw, "url", noi)
        if not url.startswith("https://"):
            raise ValueError(f"{noi}: url phải là link https tới văn bản chính thức")
        trang_thai = chuoi_bat_buoc(raw, "status", noi)
        if trang_thai not in TRANG_THAI_HOP_LE:
            raise ValueError(f"{noi}: status phải thuộc {sorted(TRANG_THAI_HOP_LE)}")
        if ma in ket_qua:
            raise ValueError(f"{noi}: trùng mã nguồn pháp lý {ma}")
        ket_qua[ma] = LegalSource(
            id=ma,
            title=chuoi_bat_buoc(raw, "title", noi),
            url=url,
            status=trang_thai,
            checked_at=ngay_iso(raw.get("checked_at"), noi),
        )
    return ket_qua


def parse_ref(ref: str) -> tuple[str, str]:
    """Tách căn cứ "MÃ:điều khoản".

    "LAW-P2P-2025:Điều 11 khoản 1 điểm c" -> ("LAW-P2P-2025", "Điều 11 khoản 1 điểm c").
    Không có phần sau dấu ":" -> (mã, ""). Khoảng trắng thừa trong phần điều khoản được gộp.
    """
    if not isinstance(ref, str) or not ref.strip():
        raise ValueError("Căn cứ pháp lý trống")
    ma, _, dieu = ref.strip().partition(":")
    ma = ma.strip()
    if not MA_HOP_LE.fullmatch(ma):
        raise ValueError(f"Căn cứ pháp lý sai dạng 'MA-NGUON:Điều ...': {ref!r}")
    return ma, " ".join(dieu.split())


def citation_for(ref: str, sources: Mapping[str, LegalSource]) -> Citation:
    """Trích dẫn `LUAT` cho một căn cứ; mã chưa khai báo trong nguồn -> ValueError.

    Ném lỗi thay vì trả trích dẫn thiếu link: test đồng bộ kho kiến thức (Task 8) bắt lỗi này
    lúc phát triển, nên lúc chạy thật không có căn cứ nào trỏ vào hư không.
    """
    ma, dieu = parse_ref(ref)
    nguon = sources.get(ma)
    if nguon is None:
        raise ValueError(f"Căn cứ {ref!r} trỏ tới nguồn chưa có trong legal_sources.yaml")
    return Citation(
        id=f"{ma}:{dieu}" if dieu else ma,
        kind="LUAT",
        label=f"{nguon.title}, {dieu}" if dieu else nguon.title,
        url=nguon.url,
        checked_at=nguon.checked_at,
    )


def citations_for_chunk(chunk: KnowledgeChunk, sources: Mapping[str, LegalSource]) -> list[Citation]:
    """Mọi trích dẫn của một đoạn kiến thức, không trùng `id`.

    - `CHINH_SACH`: một trích dẫn chính sách (id = id đoạn, kèm phiên bản chính sách).
    - `CHUA_XAC_MINH`: một trích dẫn "chưa xác minh nguồn gốc".
    - Mọi đoạn: thêm trích dẫn `LUAT` cho từng `legal_refs` (đoạn chính sách có thể dựa trên luật).
    """
    ket_qua: list[Citation] = []
    if chunk.kind == "CHINH_SACH":
        ket_qua.append(
            Citation(
                id=chunk.id,
                kind="CHINH_SACH",
                label=f"Chính sách FINORA: {chunk.title} ({chunk.version})",
                url=None,
                checked_at=chunk.checked_at,
            )
        )
    elif chunk.kind == "CHUA_XAC_MINH":
        ket_qua.append(
            Citation(
                id=chunk.id,
                kind="CHUA_XAC_MINH",
                label=f"Chưa xác minh nguồn gốc: {chunk.title}",
                url=None,
                checked_at=chunk.checked_at,
            )
        )
    da_co = {citation.id for citation in ket_qua}
    for ref in chunk.legal_refs:
        citation = citation_for(ref, sources)
        if citation.id not in da_co:
            da_co.add(citation.id)
            ket_qua.append(citation)
    return ket_qua
