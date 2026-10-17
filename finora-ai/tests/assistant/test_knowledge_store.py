"""Test đọc kho kiến thức YAML (AI-CHAT-001, Task 7).

File kho kiến thức do người viết tay, nên trọng tâm là CHẶN file sai khuôn ngay lúc nạp:
gõ sai vai trò làm đoạn biến mất khỏi kết quả tìm kiếm mà không ai hay, gõ sai căn cứ làm
trích dẫn trỏ vào hư không.
"""

from textwrap import dedent

import pytest

from app.services.assistant.knowledge.store import KnowledgeChunk, load_chunks

DOAN_HOP_LE = dedent(
    """\
    - id: NV-TAT-TOAN-01
      title: Phí tất toán trước hạn
      roles: [BORROWER]
      kind: CHINH_SACH
      body: >-
        Tất toán trước hạn là trả hết nợ gốc còn lại trước ngày đáo hạn.
      legal_refs: []
      source: docs/LOAN-SERVICING-POLICY.md §3.3
      version: LOAN_SERVICING_POLICY_V1
      checked_at: 2026-10-15
      keywords: [tất toán, trả hết]
    """
)


def _ghi(thu_muc, ten, noi_dung):
    (thu_muc / ten).write_text(dedent(noi_dung), encoding="utf-8")


def _mot_doan(**ghi_de) -> str:
    """Một đoạn hợp lệ dạng YAML, cho phép thay/xóa từng trường để thử lỗi."""
    truong = {
        "id": "QT-01",
        "title": "Ngưỡng tự duyệt",
        "roles": "[ADMIN]",
        "kind": "CHINH_SACH",
        "body": "Hồ sơ từ 75 điểm được tự duyệt.",
        "source": "app/services/credit/product_config",
        "version": "CREDIT_POLICY_V1",
        "checked_at": "2026-10-15",
    }
    truong.update(ghi_de)
    dong = [f"  {k}: {v}" for k, v in truong.items() if v is not None]
    dong[0] = "- " + dong[0].lstrip()
    return "\n".join(dong) + "\n"


def test_doc_doan_hop_le_va_chuan_hoa_kieu(tmp_path):
    _ghi(tmp_path, "nguoi_vay.yaml", DOAN_HOP_LE)

    [doan] = load_chunks(tmp_path)

    assert doan == KnowledgeChunk(
        id="NV-TAT-TOAN-01",
        title="Phí tất toán trước hạn",
        roles=frozenset({"BORROWER"}),
        kind="CHINH_SACH",
        body="Tất toán trước hạn là trả hết nợ gốc còn lại trước ngày đáo hạn.",
        legal_refs=(),
        source="docs/LOAN-SERVICING-POLICY.md §3.3",
        version="LOAN_SERVICING_POLICY_V1",
        checked_at="2026-10-15",  # YAML đọc thành date, store đổi về chuỗi
        keywords=("tất toán", "trả hết"),
    )


def test_doc_nhieu_file_theo_thu_tu_ten_va_bo_qua_file_rong(tmp_path):
    _ghi(tmp_path, "b.yaml", _mot_doan(id="B-01"))
    _ghi(tmp_path, "a.yaml", _mot_doan(id="A-01"))
    _ghi(tmp_path, "rong.yaml", "")
    _ghi(tmp_path, "ghi-chu.txt", "không phải yaml")

    assert [doan.id for doan in load_chunks(tmp_path)] == ["A-01", "B-01"]


def test_truong_tuy_chon_mac_dinh_rong_va_luat_co_can_cu(tmp_path):
    _ghi(
        tmp_path,
        "phap_ly.yaml",
        _mot_doan(
            id="PL-KY-HAN-01",
            kind="LUAT",
            roles="[ALL]",
            legal_refs='["LAW-P2P-2025:Điều 11 khoản 1 điểm c"]',
            checked_at="'2026-10-10'",
        ),
    )

    [doan] = load_chunks(tmp_path)

    assert doan.legal_refs == ("LAW-P2P-2025:Điều 11 khoản 1 điểm c",)
    assert doan.keywords == ()
    assert doan.roles == frozenset({"ALL"})
    assert doan.checked_at == "2026-10-10"


def test_trung_id_giua_hai_file_bi_tu_choi(tmp_path):
    _ghi(tmp_path, "a.yaml", _mot_doan(id="TRUNG-01"))
    _ghi(tmp_path, "b.yaml", _mot_doan(id="TRUNG-01"))

    with pytest.raises(ValueError, match="Trùng id đoạn kiến thức TRUNG-01"):
        load_chunks(tmp_path)


@pytest.mark.parametrize(
    "noi_dung, thong_bao",
    [
        ("- id: [chưa đóng\n", "YAML không hợp lệ"),
        ("id: QT-01\n", "gốc file phải là danh sách"),
        ("- chỉ là chuỗi\n", "phải là mapping"),
        (_mot_doan(body=None), "thiếu trường"),
        (_mot_doan(roles="[BORROWER, KHACH]"), "vai trò không hợp lệ"),
        (_mot_doan(roles="[]"), "không được rỗng"),
        (_mot_doan(roles="[ALL, ADMIN]"), "ALL"),
        (_mot_doan(kind="FAQ"), "kind phải thuộc"),
        (_mot_doan(kind="LUAT"), "ít nhất một legal_ref"),
        (_mot_doan(legal_refs="['Điều 11']"), "legal_ref sai dạng"),
        (_mot_doan(checked_at="15/10/2026"), "YYYY-MM-DD"),
        (_mot_doan(checked_at="'2026-02-30'"), "YYYY-MM-DD"),
        (_mot_doan(checked_at="2026-02-30"), "YAML không hợp lệ"),
        (_mot_doan(id="nv-01"), "id chỉ gồm"),
        (_mot_doan(legal_ref="[]"), "trường lạ"),
        (_mot_doan(keywords="[no, tất toán]"), "chỉ chứa chuỗi"),
        (_mot_doan(title="123"), "phải là chuỗi"),
    ],
)
def test_file_sai_khuon_bi_tu_choi_kem_noi_loi(tmp_path, noi_dung, thong_bao):
    _ghi(tmp_path, "loi.yaml", noi_dung)

    with pytest.raises(ValueError, match=thong_bao) as loi:
        load_chunks(tmp_path)

    assert "loi.yaml" in str(loi.value)


def test_thu_muc_khong_ton_tai(tmp_path):
    with pytest.raises(ValueError, match="Không thấy thư mục"):
        load_chunks(tmp_path / "khong-co")


def test_kho_mac_dinh_nap_duoc_va_khong_trung_id():
    doan = load_chunks()

    assert doan, "knowledge/chunks phải có ít nhất một đoạn"
    assert len({d.id for d in doan}) == len(doan)
