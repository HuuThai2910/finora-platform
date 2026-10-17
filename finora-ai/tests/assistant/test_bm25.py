"""Test tách từ tiếng Việt và tìm kiếm BM25 của kho kiến thức (AI-CHAT-001, Task 7).

Ba tình huống người dùng thật: gõ có dấu, gõ không dấu, và hỏi đúng chủ đề của vai trò
khác (người vay không được nhận đoạn chỉ dành cho quản trị). Thêm một test tính tay công
thức BM25 Okapi để chắc điểm đúng chuẩn chứ không chỉ "có vẻ xếp đúng".
"""

import math
import unicodedata

import pytest

from app.services.assistant.knowledge.bm25 import (
    Bm25Index,
    bo_dau,
    get_index,
    tokenize_vi,
)
from app.services.assistant.knowledge.store import KnowledgeChunk
from app.services.assistant.principal import Toolset


def _doan(id_, title, body, roles, keywords=(), kind="CHINH_SACH", legal_refs=()):
    return KnowledgeChunk(
        id=id_,
        title=title,
        roles=frozenset(roles),
        kind=kind,
        body=body,
        legal_refs=tuple(legal_refs),
        source="test",
        version="TEST_V1",
        checked_at="2026-10-15",
        keywords=tuple(keywords),
    )


KHO = [
    _doan(
        "NV-TAT-TOAN-01",
        "Phí tất toán trước hạn",
        "Tất toán trước hạn là trả hết nợ gốc còn lại trước ngày đáo hạn. Phí tất toán tính "
        "trên gốc còn lại theo kỳ hạn của khoản vay.",
        ["BORROWER"],
        ["tất toán", "trả hết", "trả trước hạn"],
    ),
    _doan(
        "NDT-KHOP-LENH-01",
        "Vì sao lệnh chưa khớp",
        "Lệnh mua chỉ khớp khi giá mua chạm giá bán tốt nhất trong sổ lệnh. Lệnh chạm lệnh của "
        "chính mình thì phần còn lại bị hủy.",
        ["INVESTOR"],
        ["khớp lệnh", "sổ lệnh"],
    ),
    _doan(
        "QT-NGUONG-01",
        "Ngưỡng tự duyệt và tự từ chối",
        "Hồ sơ từ 75 điểm được tự duyệt, dưới 65 điểm tự từ chối, từ 65 đến 74 chờ chuyên viên "
        "thẩm định. Hạng A đến E là chính sách giá.",
        ["ADMIN"],
        ["ngưỡng duyệt", "thẩm định"],
    ),
    _doan(
        "CHUNG-PIN-01",
        "Mã PIN bị khóa",
        "Nhập sai mã PIN 5 lần liên tiếp thì PIN bị khóa 15 phút. Có thể chờ hết thời gian hoặc "
        "dùng Quên mã PIN.",
        ["ALL"],
        ["khóa PIN", "quên PIN"],
        kind="HUONG_DAN",
    ),
    _doan(
        "PL-KY-HAN-01",
        "Kỳ hạn vay tối đa",
        "Hợp đồng vay ngang hàng trong cơ chế thử nghiệm không quá 02 năm, tức 24 tháng.",
        ["ALL"],
        ["kỳ hạn tối đa", "24 tháng"],
        kind="LUAT",
        legal_refs=["LAW-P2P-2025:Điều 11 khoản 1 điểm c"],
    ),
]


@pytest.fixture(scope="module")
def chi_muc():
    return Bm25Index(KHO)


def _ids(ket_qua):
    return [doan.id for doan, _ in ket_qua]


def test_tokenize_am_tiet_bigram_va_ban_bo_dau():
    assert tokenize_vi("Tất toán") == ["tất", "toán", "tất toán", "tat", "toan", "tat toan"]


def test_tokenize_chuan_hoa_nfc_va_chu_thuong():
    to_hop = unicodedata.normalize("NFD", "TẤT TOÁN")
    assert tokenize_vi(to_hop) == tokenize_vi("tất toán")


def test_tokenize_d_gach_thanh_d_va_bo_dau_cau():
    tokens = tokenize_vi("Đáo hạn, ĐƯỢC!")
    assert "đáo" in tokens and "dao" in tokens and "dao han" in tokens
    assert "," not in "".join(tokens) and "!" not in "".join(tokens)
    assert bo_dau("Đồng ý đổi") == "Dong y doi"


def test_tokenize_bo_am_tiet_hu_nhung_giu_bigram():
    tokens = tokenize_vi("Tôi là ai")
    assert "tôi" not in tokens and "là" not in tokens
    assert "ai" in tokens  # trùng "AI" nên không coi là từ hư
    assert "tôi là" in tokens and "toi la" in tokens


def test_tokenize_tu_hu_khong_dau_khong_nuot_tu_co_dau():
    # "toi" gõ không dấu là "tôi" (từ hư) nên bị bỏ; "tối" có dấu thì giữ.
    assert "toi" not in tokenize_vi("toi muon vay")
    assert "tối" in tokenize_vi("kỳ hạn tối đa")


def test_tim_cau_co_dau(chi_muc):
    ket_qua = chi_muc.search("Phí tất toán trước hạn là bao nhiêu?", toolsets=frozenset({"BORROWER"}))
    assert _ids(ket_qua)[0] == "NV-TAT-TOAN-01"


def test_tim_cau_khong_dau(chi_muc):
    ket_qua = chi_muc.search("phi tat toan truoc han", toolsets=frozenset({"BORROWER"}))
    assert _ids(ket_qua)[0] == "NV-TAT-TOAN-01"


def test_loc_theo_vai_tro(chi_muc):
    ket_qua = chi_muc.search("phí tất toán trước hạn", toolsets=frozenset({"INVESTOR"}))
    assert "NV-TAT-TOAN-01" not in _ids(ket_qua)


def test_doan_all_hien_cho_moi_vai_tro(chi_muc):
    for vai_tro in ("BORROWER", "INVESTOR", "ADMIN"):
        ket_qua = chi_muc.search("mã PIN bị khóa", toolsets=frozenset({vai_tro}))
        assert _ids(ket_qua)[0] == "CHUNG-PIN-01"


def test_nhan_toolset_enum(chi_muc):
    ket_qua = chi_muc.search(
        "vì sao hồ sơ 70 điểm phải chờ thẩm định", toolsets=frozenset({Toolset.ADMIN})
    )
    assert _ids(ket_qua)[0] == "QT-NGUONG-01"


def test_toolset_rong_chi_con_doan_all(chi_muc):
    ket_qua = chi_muc.search("thẩm định tất toán khớp lệnh kỳ hạn", toolsets=frozenset(), min_score=0)
    assert set(_ids(ket_qua)) <= {"CHUNG-PIN-01", "PL-KY-HAN-01"}


def test_min_score_loai_cau_khong_lien_quan(chi_muc):
    vai_tro = frozenset({"BORROWER", "INVESTOR", "ADMIN"})
    assert chi_muc.search("tôi có là gì của bạn", toolsets=vai_tro) == []
    assert chi_muc.search("tất toán", toolsets=frozenset({"BORROWER"}), min_score=1000) == []


def test_khop_tinh_co_mot_am_tiet_bi_loai(chi_muc):
    # "thời" có trong "chờ hết thời gian" (đoạn PIN), "quá" có trong "không quá 02 năm":
    # mỗi đoạn chỉ khớp một âm tiết của câu hỏi nhiều âm tiết -> không phải đoạn liên quan.
    vai_tro = frozenset({"BORROWER", "INVESTOR", "ADMIN"})
    assert chi_muc.search("thời tiết hôm nay đẹp quá", toolsets=vai_tro, min_score=0) == []


def test_cau_hoi_mot_am_tiet_van_tim_duoc(chi_muc):
    ket_qua = chi_muc.search("PIN?", toolsets=frozenset({"INVESTOR"}))
    assert _ids(ket_qua) == ["CHUNG-PIN-01"]


def test_limit_va_cau_rong(chi_muc):
    vai_tro = frozenset({"BORROWER", "INVESTOR", "ADMIN"})
    cau = "lệnh khớp tất toán mã PIN kỳ hạn thẩm định"  # khớp đủ 5 đoạn
    assert len(chi_muc.search(cau, toolsets=vai_tro, limit=10, min_score=0)) == 5
    assert len(chi_muc.search(cau, toolsets=vai_tro, min_score=0)) == 4  # mặc định limit=4
    assert len(chi_muc.search(cau, toolsets=vai_tro, limit=1, min_score=0)) == 1
    assert chi_muc.search("?!...", toolsets=vai_tro) == []
    assert chi_muc.search("tất toán", toolsets=vai_tro, limit=0) == []


def test_ket_qua_sap_giam_dan_theo_diem(chi_muc):
    vai_tro = frozenset({"BORROWER", "INVESTOR", "ADMIN"})
    cau = "lệnh khớp tất toán mã PIN kỳ hạn thẩm định"
    diem = [d for _, d in chi_muc.search(cau, toolsets=vai_tro, limit=10, min_score=0)]
    assert len(diem) == 5
    assert diem == sorted(diem, reverse=True)


def test_tu_khoa_va_tieu_de_duoc_nhan_trong_so():
    chi_trong_than = _doan("A-01", "Mục một", "Khoản phí trả trước được tính riêng.", ["ALL"])
    trong_tieu_de = _doan("B-01", "Phí trả trước", "Khoản này được tính riêng.", ["ALL"])
    trong_tu_khoa = _doan("C-01", "Mục ba", "Khoản này được tính riêng.", ["ALL"], ["phí trả trước"])
    chi_muc = Bm25Index([chi_trong_than, trong_tieu_de, trong_tu_khoa])

    ket_qua = chi_muc.search("phí trả trước", toolsets=frozenset({"BORROWER"}), min_score=0)

    assert _ids(ket_qua) == ["C-01", "B-01", "A-01"]


def test_cong_thuc_bm25_okapi_tinh_tay():
    # Không dấu, không từ hư để đếm token bằng mắt được.
    # Đoạn 1: tiêu đề "alpha" (x2) + thân "beta gamma" -> alpha:2, beta:1, gamma:1, "beta gamma":1 -> |D|=5
    # Đoạn 2: tiêu đề "delta" (x2) + thân "beta beta epsilon" -> delta:2, beta:2, epsilon:1,
    #         "beta beta":1, "beta epsilon":1 -> |D|=7. avgdl = 6, N = 2.
    mot = _doan("X-01", "alpha", "beta gamma", ["ALL"])
    hai = _doan("X-02", "delta", "beta beta epsilon", ["ALL"])
    chi_muc = Bm25Index([mot, hai], k1=1.5, b=0.75)

    [(doan, diem)] = chi_muc.search("gamma", toolsets=frozenset(), min_score=0)

    idf = math.log((2 - 1 + 0.5) / (1 + 0.5) + 1)
    mong_doi = idf * 1 * 2.5 / (1 + 1.5 * (1 - 0.75 + 0.75 * 5 / 6))
    assert doan.id == "X-01"
    assert diem == pytest.approx(mong_doi)

    # "beta" có ở cả hai đoạn: idf vẫn dương, đoạn 2 (f=2) điểm cao hơn đoạn 1 (f=1).
    ket_qua = chi_muc.search("beta", toolsets=frozenset(), min_score=0)
    assert _ids(ket_qua) == ["X-02", "X-01"]
    assert all(d > 0 for _, d in ket_qua)


def test_chi_muc_rong():
    assert Bm25Index([]).search("tất toán", toolsets=frozenset({"BORROWER"})) == []


def test_get_index_dung_kho_mac_dinh_va_cache():
    chi_muc = get_index()
    assert len(chi_muc) >= 1
    assert get_index() is chi_muc
