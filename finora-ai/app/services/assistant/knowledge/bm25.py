"""Tìm đoạn kiến thức bằng BM25 cho tiếng Việt, chạy hoàn toàn trong tiến trình.

Vì sao BM25 mà không dùng embedding
-----------------------------------
Kho chỉ 50–70 đoạn, câu hỏi chính sách thường chứa đúng thuật ngữ ("tất toán", "nhóm nợ",
"Auto-Invest"). BM25 — xếp hạng theo "từ hiếm trong kho mà xuất hiện nhiều trong đoạn thì
đoạn đó liên quan" — đủ tốt, chạy vài mili-giây, không cần model hay dịch vụ ngoài (spec
mục 1 để vector DB/embedding ra ngoài phạm vi). Kết quả lại giải thích được: điểm cao vì
khớp những từ nào.

Tách từ tiếng Việt
------------------
Tiếng Việt viết tách âm tiết ("tất toán" là hai âm tiết, một từ). Không dùng bộ tách từ
nặng; thay vào đó mỗi văn bản sinh:
1. âm tiết (bỏ các âm tiết hư như "là", "của", "tôi" — chúng có trong gần mọi câu, chỉ làm
   nhiễu điểm và kéo đoạn không liên quan vượt ngưỡng);
2. cặp âm tiết liền nhau (bigram, giữ cả âm tiết hư) để "tất toán" khớp mạnh hơn hai âm tiết
   rời rạc;
3. bản bỏ dấu (đ→d) của mọi token trên, để người gõ không dấu "tat toan" vẫn tìm thấy.
Văn bản được chuẩn hóa NFC trước, vì cùng chữ "toán" có thể đến ở dạng dựng sẵn hoặc tổ hợp.

Tiêu đề và từ khóa được nhân trọng số (đếm nhiều lần) vì người viết kho đặt ở đó đúng
những từ người dùng hay hỏi.

Chặn khớp tình cờ
-----------------
Với kho vài chục đoạn, một âm tiết hiếm khớp tình cờ đã đủ vượt ngưỡng điểm 1,0 ("thời tiết
hôm nay" khớp chữ "thời" trong "chờ hết thời gian"). Đoạn sai kéo theo trích dẫn sai, nên
ngoài điểm BM25 còn đòi đoạn phải khớp ít nhất `SO_AM_TIET_KHOP_TOI_THIEU` âm tiết nội dung
khác nhau của câu hỏi (câu hỏi chỉ có một âm tiết nội dung thì đòi một).
"""

from __future__ import annotations

import math
import re
import unicodedata
from collections import Counter
from collections.abc import Iterable, Sequence
from functools import lru_cache

from app.services.assistant.knowledge.store import KnowledgeChunk, load_chunks

TRONG_SO_TIEU_DE = 2
TRONG_SO_TU_KHOA = 3
SO_AM_TIET_KHOP_TOI_THIEU = 2

_TACH = re.compile(r"\W+")

# Âm tiết hư chỉ bị bỏ ở mức âm tiết đơn; bigram vẫn giữ ("không được", "bị khóa").
# Cố ý KHÔNG có: "ai" (trùng "AI"), "lại" (bỏ dấu thành "lai" trùng "lãi"), "bị", "chờ".
TU_DUNG: frozenset[str] = frozenset(
    {
        "à", "ạ", "ơi", "nhé", "nhỉ", "hả", "vậy", "thế", "thì", "là", "và", "của", "có",
        "cho", "các", "những", "một", "này", "đó", "với", "mà", "tôi", "mình", "bạn", "em",
        "anh", "chị", "gì", "sao", "nào", "đã", "đang", "sẽ", "rồi", "được", "không", "hay",
        "hoặc", "khi", "để", "từ", "trong", "ra", "vào", "cũng", "nữa", "thôi", "nhưng",
        "nếu", "bao", "nhiêu",
    }
)


def bo_dau(text: str) -> str:
    """Bỏ dấu tiếng Việt: "Tất toán đáo hạn" -> "Tat toan dao han" (đ/Đ -> d/D)."""
    tach = unicodedata.normalize("NFD", text)
    khong_dau = "".join(ky_tu for ky_tu in tach if unicodedata.category(ky_tu) != "Mn")
    return unicodedata.normalize("NFC", khong_dau.replace("đ", "d").replace("Đ", "D"))


_TU_DUNG_KHONG_DAU = frozenset(bo_dau(tu) for tu in TU_DUNG)


def _la_tu_dung(am_tiet: str) -> bool:
    # Âm tiết gõ không dấu ("toi", "la") so với bản không dấu của danh sách; âm tiết có
    # dấu thì so đúng nguyên dạng, để "tối" (tối đa) không bị coi là "tôi".
    if am_tiet in TU_DUNG:
        return True
    return am_tiet == bo_dau(am_tiet) and am_tiet in _TU_DUNG_KHONG_DAU


def _am_tiet(text: str) -> list[str]:
    """Âm tiết đã chuẩn hóa NFC + chữ thường, bỏ dấu câu."""
    return [t for t in _TACH.split(unicodedata.normalize("NFC", text).lower()) if t]


def tokenize_vi(text: str) -> list[str]:
    """Tách văn bản thành token cho BM25: âm tiết + bigram âm tiết + bản bỏ dấu.

    tokenize_vi("Tất toán") ->
        ["tất", "toán", "tất toán", "tat", "toan", "tat toan"]
    """
    am_tiet = _am_tiet(text)
    co_dau = [t for t in am_tiet if not _la_tu_dung(t)]
    co_dau.extend(f"{a} {b}" for a, b in zip(am_tiet, am_tiet[1:]))
    # Token vốn không dấu ("pin", "24") không cần bản thứ hai.
    return co_dau + [k for t in co_dau if (k := bo_dau(t)) != t]


def _ten_vai_tro(toolsets: Iterable[object]) -> frozenset[str]:
    # Nhận cả chuỗi lẫn `Toolset` (str Enum) mà không import principal, tránh vòng phụ thuộc.
    return frozenset(str(getattr(t, "value", t)) for t in toolsets)


class Bm25Index:
    """Chỉ mục BM25 Okapi trên các đoạn kiến thức, dựng một lần lúc khởi động.

    Điểm một đoạn D với câu hỏi Q (mỗi token hỏi tính một lần):
        Σ idf(q) · f(q,D)·(k1+1) / (f(q,D) + k1·(1 − b + b·|D|/avgdl))
        idf(q) = ln((N − n(q) + 0,5) / (n(q) + 0,5) + 1)
    f là tần suất đã nhân trọng số tiêu đề/từ khóa; |D| là tổng tần suất của đoạn. Dạng idf
    "+1" luôn dương, nên từ có ở hơn nửa kho vẫn không làm điểm âm.
    """

    def __init__(self, chunks: Sequence[KnowledgeChunk], k1: float = 1.5, b: float = 0.75) -> None:
        self._chunks: tuple[KnowledgeChunk, ...] = tuple(chunks)
        self._k1 = k1
        self._b = b
        self._tan_suat: list[Counter[str]] = [self._dem(chunk) for chunk in self._chunks]
        self._do_dai: list[int] = [sum(tf.values()) for tf in self._tan_suat]
        so_doan = len(self._chunks)
        self._do_dai_tb = sum(self._do_dai) / so_doan if so_doan else 0.0
        so_doan_chua: Counter[str] = Counter()
        for tf in self._tan_suat:
            so_doan_chua.update(tf.keys())
        self._idf: dict[str, float] = {
            token: math.log((so_doan - n + 0.5) / (n + 0.5) + 1.0)
            for token, n in so_doan_chua.items()
        }

    @staticmethod
    def _dem(chunk: KnowledgeChunk) -> Counter[str]:
        tf: Counter[str] = Counter(tokenize_vi(chunk.body))
        for token in tokenize_vi(chunk.title):
            tf[token] += TRONG_SO_TIEU_DE
        # Mỗi từ khóa tách riêng để bigram không nối hai từ khóa khác nhau.
        for tu_khoa in chunk.keywords:
            for token in tokenize_vi(tu_khoa):
                tf[token] += TRONG_SO_TU_KHOA
        return tf

    def __len__(self) -> int:
        return len(self._chunks)

    def _diem(self, vi_tri: int, tokens: frozenset[str]) -> float:
        tf = self._tan_suat[vi_tri]
        chuan_do_dai = self._k1 * (1 - self._b + self._b * self._do_dai[vi_tri] / self._do_dai_tb)
        diem = 0.0
        for token in tokens:
            f = tf.get(token, 0)
            if f:
                diem += self._idf[token] * f * (self._k1 + 1) / (f + chuan_do_dai)
        return diem

    def _so_am_tiet_khop(self, vi_tri: int, am_tiet_hoi: Sequence[frozenset[str]]) -> int:
        tf = self._tan_suat[vi_tri]
        return sum(1 for dang in am_tiet_hoi if any(d in tf for d in dang))

    def search(
        self,
        query: str,
        *,
        toolsets: frozenset[str],
        limit: int = 4,
        min_score: float = 1.0,
    ) -> list[tuple[KnowledgeChunk, float]]:
        """Tối đa `limit` đoạn hợp vai trò, điểm ≥ `min_score`, cao trước (bằng điểm xếp theo id).

        Đoạn hợp vai trò khi `roles` có "ALL" hoặc giao với `toolsets` (chuỗi hoặc `Toolset`);
        `toolsets` rỗng chỉ còn đoạn "ALL". Đoạn phải khớp ít nhất
        min(SO_AM_TIET_KHOP_TOI_THIEU, số âm tiết nội dung của câu hỏi) âm tiết khác nhau.
        Câu hỏi không có âm tiết nội dung nào (toàn dấu câu/từ hư) -> [].
        """
        if not self._chunks or limit <= 0:
            return []
        # Mỗi âm tiết nội dung của câu hỏi, kèm bản bỏ dấu, để đếm độ phủ.
        am_tiet_hoi = [
            frozenset({t, bo_dau(t)})
            for t in dict.fromkeys(_am_tiet(query))
            if not _la_tu_dung(t)
        ]
        if not am_tiet_hoi:
            return []
        can_khop = min(SO_AM_TIET_KHOP_TOI_THIEU, len(am_tiet_hoi))
        tokens = frozenset(tokenize_vi(query))
        vai_tro = _ten_vai_tro(toolsets)
        ket_qua: list[tuple[KnowledgeChunk, float]] = []
        for vi_tri, chunk in enumerate(self._chunks):
            if "ALL" not in chunk.roles and not (chunk.roles & vai_tro):
                continue
            if self._so_am_tiet_khop(vi_tri, am_tiet_hoi) < can_khop:
                continue
            diem = self._diem(vi_tri, tokens)
            if diem > 0 and diem >= min_score:
                ket_qua.append((chunk, diem))
        ket_qua.sort(key=lambda cap: (-cap[1], cap[0].id))
        return ket_qua[:limit]


@lru_cache(maxsize=1)
def get_index() -> Bm25Index:
    """Chỉ mục dựng từ `knowledge/chunks/` một lần cho cả tiến trình (uvicorn một worker)."""
    return Bm25Index(load_chunks())
