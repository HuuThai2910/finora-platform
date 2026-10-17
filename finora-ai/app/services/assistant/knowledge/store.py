"""Đọc kho kiến thức YAML của trợ lý thành các đoạn bất biến (`KnowledgeChunk`).

Vì sao là file YAML trong repo
------------------------------
Mỗi đoạn kiến thức là một câu trả lời chính sách/pháp lý mà trợ lý được phép nói. Để
trong repo thì mỗi lần sửa có diff, có người duyệt, có phiên bản (`version`, `checked_at`)
— đúng yêu cầu "thông tin phải chính xác" của `LAW-P2P-2025` và quy tắc cập nhật nguồn
của LEGAL-COMPLIANCE.md. Không cần DB hay vector store: 50–70 đoạn nạp vào RAM lúc khởi động.

Vì sao kiểm chặt khi đọc
------------------------
File do người viết tay. Gõ sai `roles` thì đoạn biến mất khỏi kết quả tìm kiếm của một vai
trò mà không ai hay; gõ sai `legal_refs` thì trích dẫn trỏ vào hư không. Nên mọi sai khuôn
đều ném `ValueError` kèm tên file + id đoạn ngay lúc nạp (và trong test), không âm thầm bỏ qua.

Bẫy YAML 1.1 cần nhớ khi viết nội dung: ngày không có nháy (`2026-10-15`) được đọc thành
`datetime.date` (đã xử lý ở `ngay_iso`); từ trần `no`, `yes`, `on`, `off` thành boolean —
từ khóa không dấu như "no" phải để trong nháy, nếu không sẽ bị báo lỗi kiểu.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from typing import Any

import yaml

THU_MUC_CHUNKS = Path(__file__).resolve().parent / "chunks"

VAI_TRO_HOP_LE = frozenset({"BORROWER", "INVESTOR", "ADMIN", "ALL"})
LOAI_HOP_LE = frozenset({"LUAT", "CHINH_SACH", "CHUA_XAC_MINH", "HUONG_DAN"})

# Id đoạn và mã nguồn pháp lý: chữ hoa/số nối bằng "-" (NV-TAT-TOAN-01, LAW-P2P-2025).
MA_HOP_LE = re.compile(r"^[A-Z0-9]+(?:-[A-Z0-9]+)*$")
_THAM_CHIEU = re.compile(r"^[A-Z0-9]+(?:-[A-Z0-9]+)*(?::\s*\S.*)?$")
_NGAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")

_BAT_BUOC = ("id", "title", "roles", "kind", "body", "source", "version", "checked_at")
_TUY_CHON = ("legal_refs", "keywords")
_TRUONG_HOP_LE = frozenset(_BAT_BUOC + _TUY_CHON)


@dataclass(frozen=True)
class KnowledgeChunk:
    """Một đoạn kiến thức đã kiểm khuôn. `roles` ⊆ VAI_TRO_HOP_LE, `kind` ∈ LOAI_HOP_LE."""

    id: str
    title: str
    roles: frozenset[str]
    kind: str
    body: str
    legal_refs: tuple[str, ...]
    source: str
    version: str
    checked_at: str
    keywords: tuple[str, ...]


def ngay_iso(value: Any, noi: str) -> str:
    """Chuẩn hóa ngày về chuỗi `YYYY-MM-DD`.

    PyYAML đọc `2026-10-15` không có nháy thành `datetime.date`; người viết có thể để nháy
    hoặc không, nên chấp nhận cả hai. Ngày sai lịch (`2026-02-30`) bị từ chối.
    """
    if isinstance(value, datetime):
        value = value.date()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, str) and _NGAY.fullmatch(value.strip()):
        try:
            return date.fromisoformat(value.strip()).isoformat()
        except ValueError:
            pass
    raise ValueError(f"{noi}: ngày phải có dạng YYYY-MM-DD, nhận {value!r}")


def doc_yaml_danh_sach(path: Path) -> list[dict[str, Any]]:
    """Đọc file YAML có gốc là danh sách mapping; file rỗng coi như danh sách rỗng.

    Dùng `yaml.safe_load` (không bao giờ `yaml.load`): file kho kiến thức không được phép
    dựng object Python tùy ý.
    """
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (yaml.YAMLError, ValueError) as exc:
        # PyYAML ném ValueError (không phải YAMLError) khi ngày không nháy sai lịch, ví dụ
        # `checked_at: 2026-02-30`; gộp chung để thông báo luôn có tên file.
        raise ValueError(f"{path.name}: YAML không hợp lệ: {exc}") from exc
    if data is None:
        return []
    if not isinstance(data, list):
        raise ValueError(f"{path.name}: gốc file phải là danh sách")
    for vi_tri, muc in enumerate(data):
        if not isinstance(muc, dict):
            raise ValueError(f"{path.name}: phần tử thứ {vi_tri} phải là mapping")
    return data


def chuoi_bat_buoc(raw: dict[str, Any], truong: str, noi: str) -> str:
    """Chuỗi không rỗng; số hay boolean (do YAML tự đổi kiểu) đều bị từ chối."""
    gia_tri = raw.get(truong)
    if not isinstance(gia_tri, str) or not gia_tri.strip():
        raise ValueError(f"{noi}: trường '{truong}' phải là chuỗi không rỗng")
    return gia_tri.strip()


def _danh_sach_chuoi(raw: dict[str, Any], truong: str, noi: str) -> tuple[str, ...]:
    gia_tri = raw.get(truong)
    if gia_tri is None:
        return ()
    if not isinstance(gia_tri, list):
        raise ValueError(f"{noi}: trường '{truong}' phải là danh sách")
    ket_qua: list[str] = []
    for muc in gia_tri:
        if not isinstance(muc, str) or not muc.strip():
            raise ValueError(f"{noi}: '{truong}' chỉ chứa chuỗi không rỗng, gặp {muc!r}")
        ket_qua.append(muc.strip())
    return tuple(ket_qua)


def _doc_doan(raw: dict[str, Any], path: Path) -> KnowledgeChunk:
    noi = f"{path.name} / {raw.get('id', '<thiếu id>')}"
    thua = set(raw) - _TRUONG_HOP_LE
    if thua:
        # Bắt lỗi gõ tên trường (legal_ref, keyword...) thay vì bỏ qua cả trường.
        raise ValueError(f"{noi}: trường lạ {sorted(thua)}")
    thieu = [truong for truong in _BAT_BUOC if truong not in raw]
    if thieu:
        raise ValueError(f"{noi}: thiếu trường {thieu}")

    ma = chuoi_bat_buoc(raw, "id", noi)
    if not MA_HOP_LE.fullmatch(ma):
        raise ValueError(f"{noi}: id chỉ gồm chữ hoa, số và dấu '-'")

    vai_tro = _danh_sach_chuoi(raw, "roles", noi)
    if not vai_tro:
        raise ValueError(f"{noi}: 'roles' không được rỗng")
    la = set(vai_tro) - VAI_TRO_HOP_LE
    if la:
        raise ValueError(f"{noi}: vai trò không hợp lệ {sorted(la)}")
    if "ALL" in vai_tro and len(set(vai_tro)) > 1:
        raise ValueError(f"{noi}: 'ALL' phải đứng một mình trong roles")

    loai = chuoi_bat_buoc(raw, "kind", noi)
    if loai not in LOAI_HOP_LE:
        raise ValueError(f"{noi}: kind phải thuộc {sorted(LOAI_HOP_LE)}")

    can_cu = _danh_sach_chuoi(raw, "legal_refs", noi)
    for ref in can_cu:
        if not _THAM_CHIEU.fullmatch(ref):
            raise ValueError(f"{noi}: legal_ref sai dạng 'MA-NGUON:Điều ...': {ref!r}")
    if loai == "LUAT" and not can_cu:
        # Đoạn gắn nhãn LUẬT mà không có căn cứ thì không dựng được trích dẫn có link.
        raise ValueError(f"{noi}: đoạn kind LUAT phải có ít nhất một legal_ref")

    return KnowledgeChunk(
        id=ma,
        title=chuoi_bat_buoc(raw, "title", noi),
        roles=frozenset(vai_tro),
        kind=loai,
        body=chuoi_bat_buoc(raw, "body", noi),
        legal_refs=can_cu,
        source=chuoi_bat_buoc(raw, "source", noi),
        version=chuoi_bat_buoc(raw, "version", noi),
        checked_at=ngay_iso(raw.get("checked_at"), noi),
        keywords=_danh_sach_chuoi(raw, "keywords", noi),
    )


def load_chunks(directory: Path | None = None) -> list[KnowledgeChunk]:
    """Nạp mọi `*.yaml` trong thư mục (mặc định `knowledge/chunks/`), theo thứ tự tên file.

    Trùng id giữa hai đoạn (kể cả khác file) -> ValueError: id là khóa của trích dẫn
    `CHINH_SACH`, trùng thì không biết câu trả lời dựa vào đoạn nào.
    """
    thu_muc = Path(directory) if directory is not None else THU_MUC_CHUNKS
    if not thu_muc.is_dir():
        raise ValueError(f"Không thấy thư mục kho kiến thức: {thu_muc}")
    doan: list[KnowledgeChunk] = []
    noi_gap: dict[str, str] = {}
    for path in sorted(thu_muc.glob("*.yaml")):
        for raw in doc_yaml_danh_sach(path):
            chunk = _doc_doan(raw, path)
            if chunk.id in noi_gap:
                raise ValueError(
                    f"Trùng id đoạn kiến thức {chunk.id}: {noi_gap[chunk.id]} và {path.name}"
                )
            noi_gap[chunk.id] = path.name
            doan.append(chunk)
    return doan
