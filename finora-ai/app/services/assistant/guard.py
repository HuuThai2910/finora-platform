"""Lớp chặn của Trợ lý FINORA: lọc thông tin định danh và kiểm câu trả lời của LLM.

Prompt dặn LLM nhiều điều nhưng model miễn phí không phải lúc nào cũng nghe lời, nên các kiểm tra
quan trọng nằm ở đây và chạy bằng code — kết quả không phụ thuộc model nào đang trả lời:

- PII (LEGAL-DATA-01, LAW-DATA-2025): CCCD, số điện thoại, email, số tài khoản không được rời máy
  chủ trong payload gửi nhà cung cấp LLM và không được hiện trong câu trả lời.
- Số liệu (LAW-P2P-2025, Điều 22 khoản 3): số tiền/phần trăm trong câu trả lời phải có trong dữ kiện
  lấy từ API của lượt đó; LLM không được tự tính hay làm tròn.
- Trích dẫn: LLM không được tự viết tên văn bản/số điều; căn cứ pháp lý do code gắn từ kho kiến thức.
- Khuyến nghị đầu tư (LAW-CONSUMER-2023): nhà đầu tư không nhận lời khuyên mua/bán khoản nào.

Mọi so khớp từ khóa làm trên bản bỏ dấu để bắt được cả câu gõ không dấu ("nen dau tu khoan nao").
"""

from __future__ import annotations

import json
import re
import unicodedata
from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any

from app.schemas.assistant import Citation

AN = "[đã ẩn]"

# Đơn vị tiền hay đi liền sau con số; dùng để không nhầm "300000000 đ" là số CMND 9 chữ số.
_SAU_LA_TIEN = r"(?!\s*(?:đ(?!\w)|đồng|vnđ|vnd|%))"

_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+")
# Số tài khoản chỉ bị coi là PII khi đi kèm từ khóa: một dãy 8–16 số đứng một mình có thể là mã giao dịch.
# Không cho dấu chấm làm dấu ngăn để "Số dư tài khoản: 12.350.000 đ" không bị nhận nhầm.
_STK = re.compile(
    r"(?:\bstk\b|số\s+tài\s+khoản|so\s+tai\s+khoan|tài\s+khoản|tai\s+khoan)"
    r"[^\d\n]{0,20}?(\d(?:[\s-]?\d){7,15})(?!\d)" + _SAU_LA_TIEN,
    re.IGNORECASE,
)
# Di động Việt Nam: 0[35789] + 8 số, hoặc +84/84 thay số 0; cho phép khoảng trắng/dấu chấm giữa các nhóm.
# Có đơn vị tiền theo sau thì là số tiền: "84.350.000.000 đ" trùng khuôn 84 + 9 số của SĐT.
_SDT = re.compile(
    r"(?<![\w.,/-])(?:\+84[\s.]?|84[\s.]?|0)[35789]\d(?:[\s.]?\d){7}(?![\w/-]|[.,]\d)" + _SAU_LA_TIEN
)
# CCCD 12 số hoặc CMND cũ 9 số, đứng riêng (không dính vào mã, số thập phân hay đơn vị tiền).
# Dấu chấm/phẩy ngay sau chỉ loại khi còn số theo sau ("123456789,5"); "…001234." cuối câu vẫn bị bắt.
_CCCD = re.compile(r"(?<![\w.,/-])(?:\d{12}|\d{9})(?![\w/-]|[.,]\d)" + _SAU_LA_TIEN)

_PII = (("EMAIL", _EMAIL), ("STK", _STK), ("SDT", _SDT), ("CCCD", _CCCD))


def bo_dau(text: str) -> str:
    """Chữ thường, bỏ dấu tiếng Việt (đ → d) và gom khoảng trắng — để so từ khóa có/không dấu như nhau."""
    chu = unicodedata.normalize("NFD", text.lower().replace("đ", "d"))
    chu = "".join(c for c in chu if unicodedata.category(c) != "Mn")
    return " ".join(chu.split())


def chuan_cau(text: str) -> str:
    """Bản bỏ dấu, thay dấu câu bằng khoảng trắng, có khoảng trắng hai đầu để so cả cụm từ."""
    return " " + " ".join(re.sub(r"[^a-z0-9]+", " ", bo_dau(text)).split()) + " "


def _tim_pii(text: str) -> list[str]:
    return [loai for loai, mau in _PII if mau.search(text)]


def scrub_user_text(text: str) -> str:
    """Che CCCD/SĐT/email/STK trong câu người dùng gõ trước khi câu đó đi bất cứ đâu.

    Câu đã che vẫn đủ nghĩa để trả lời ("SĐT [đã ẩn] của tôi…"), và LLM ở nước ngoài không bao giờ
    nhận số thật. Dãy 9 số không có đơn vị tiền cũng bị che (trùng khuôn CMND cũ) — chấp nhận mất
    thông tin số tiền gõ thiếu đơn vị để không lọt định danh.
    """
    ket_qua = _EMAIL.sub(AN, text)
    ket_qua = _STK.sub(
        lambda m: m.group(0)[: m.start(1) - m.start(0)] + AN + m.group(0)[m.end(1) - m.start(0):],
        ket_qua,
    )
    ket_qua = _SDT.sub(AN, ket_qua)
    return _CCCD.sub(AN, ket_qua)


class OutboundBlocked(Exception):
    """Payload sắp gửi ra ngoài còn chứa thứ trông như PII; chỉ mang tên loại, không mang giá trị."""

    def __init__(self, kinds: Sequence[str]) -> None:
        super().__init__("Payload LLM chứa dữ liệu định danh: " + ", ".join(kinds))
        self.kinds = tuple(kinds)


def _chuoi_trong(obj: Any) -> Iterator[str]:
    """Duyệt mọi chuỗi trong message (content, arguments của tool call…) để quét từng chuỗi gốc.

    Quét chuỗi gốc thay vì json.dumps cả message: JSON đổi xuống dòng thành "\\n", khiến số điện thoại
    đầu dòng dính chữ "n" và lọt khỏi regex.

    `arguments` của tool call là chuỗi JSON do LLM viết: đọc ra rồi chỉ quét giá trị chuỗi. Giá trị số
    (`amount: 100000000`) không thể là CCCD/SĐT (mất số 0 đầu) nhưng trùng khuôn CMND 9 số, và nếu
    quét chuỗi thô thì mọi lượt mô phỏng khoản vay từ 100 triệu bị chặn oan ở vòng sau.
    """
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for key, value in obj.items():
            if key == "arguments" and isinstance(value, str):
                yield from _chuoi_trong(_doc_json_hoac_giu(value))
            else:
                yield from _chuoi_trong(value)
    elif isinstance(obj, (list, tuple)):
        for value in obj:
            yield from _chuoi_trong(value)


def _doc_json_hoac_giu(chuoi: str) -> Any:
    """JSON đọc được thì trả đối tượng; không đọc được thì giữ nguyên chuỗi để vẫn quét."""
    try:
        return json.loads(chuoi)
    except ValueError:
        return chuoi


def assert_outbound_clean(messages: list[dict[str, Any]]) -> None:
    """Chốt chặn cuối trước khi gọi nhà cung cấp LLM: thấy PII thì ném OutboundBlocked, không gửi."""
    loai_thay: list[str] = []
    for chuoi in _chuoi_trong(messages):
        for loai in _tim_pii(chuoi):
            if loai not in loai_thay:
                loai_thay.append(loai)
    if loai_thay:
        raise OutboundBlocked(loai_thay)


# ── Khuyến nghị đầu tư ─────────────────────────────────────────────────────────────────────────

# Câu hỏi xin khuyến nghị (so trên bản bỏ dấu, đủ cả câu gõ có dấu lẫn không dấu).
# "nên bán" đứng riêng không bị tính vì "tôi nên bán Note thế nào" là hỏi cách làm; chỉ tính "có nên bán".
# "khuyên" không tính khi là "khuyến mãi".
_XIN_KHUYEN_NGHI = re.compile(
    r" (?:nen (?:dau tu|mua|chon|rot)|co nen (?:ban|giu|mua|dau tu|rut)"
    r"|an toan nhat|loi nhat|lai nhat|lai cao nhat|sinh loi (?:tot|cao) nhat|tot nhat de dau tu"
    r"|khoan nao tot|chon khoan nao|mua khoan nao|dau tu (?:vao )?(?:khoan|note|cai) nao"
    r"|khuyen(?! mai)|tu van dau tu"
    r"|chac chan (?:co )?(?:lai|loi)|dam bao (?:co )?(?:lai|loi))"
)

# Cụm khuyến nghị trong câu trả lời của LLM. Không có "khuyến nghị" đứng riêng vì LLM hay viết
# "đây không phải khuyến nghị đầu tư" — câu miễn trừ đúng, không được tính là vi phạm.
_KHUYEN_NGHI_DAU_RA = re.compile(
    r" (?:nen (?:dau tu|rot von)|khuyen ban|toi khuyen|minh khuyen"
    r"|an toan nhat|loi nhat|lai nhat|sinh loi nhat|tot nhat de dau tu"
    r"|chac chan (?:co )?(?:lai|loi)|dam bao loi nhuan|cam ket loi nhuan)(?= )"
)
# "nên chọn/mua/bán/rót" chỉ là lời khuyên khi theo sau (trong 3 chữ) là một khoản, Note, số khoản, hạng hay mức
# giá cụ thể. "Bạn nên chọn tiêu chí lọc…", "nên chọn mục Lọc", "nên mua theo khả năng chịu rủi ro" là hướng dẫn
# dùng app/nhắc rủi ro; trước fix-1 các câu này bị thay bằng câu mẫu (mối lo 2 của Task 13–15).
_KHUYEN_NGHI_CO_DOI_TUONG = re.compile(
    r" nen (?:chon|mua|ban|rot) (?:\w+ ){0,3}?(?:khoan|note|notes|listing|\d+|hang [a-e]|gia \d+|cai nay|cai do)(?= )"
)


def detect_advice_request(text: str) -> bool:
    """Nhà đầu tư đang xin lời khuyên mua/bán/chọn khoản nào? Có thì trả câu từ chối mẫu."""
    return _XIN_KHUYEN_NGHI.search(chuan_cau(text)) is not None


def _co_khuyen_nghi(reply: str) -> bool:
    cau = chuan_cau(reply)
    for khop in [*_KHUYEN_NGHI_DAU_RA.finditer(cau), *_KHUYEN_NGHI_CO_DOI_TUONG.finditer(cau)]:
        truoc = cau[max(0, khop.start() - 16): khop.start() + 1]
        # "không nên đầu tư…", "bạn có nên đầu tư hay không" là câu phủ định/nhắc lại câu hỏi, không phải lời khuyên.
        if "khong" in truoc or truoc.endswith(" co "):
            continue
        return True
    return False


# ── Số liệu ────────────────────────────────────────────────────────────────────────────────────

# Một con số kèm đơn vị tùy chọn. Không bắt số dính vào chữ/mã (LA-2026-000123, R1, #12), giờ (14:30),
# ngày (25/10/2026, 2026-10-25) hay số văn bản (94/2025/NĐ-CP).
_SO = re.compile(
    r"(?<![\w.,#/:-])"
    r"(?P<so>\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d{1,3}(?:,\d{3}){2,}(?:\.\d+)?|\d+(?:[.,]\d+)?)"
    r"(?:\s*(?P<don_vi>%|phần\s*trăm|triệu|tr|tỷ|tỉ|nghìn|ngàn|k|đồng|đ|vnđ|vnd))?"
    r"(?![\w:]|[/-]\d|[.,]\d)",
    re.IGNORECASE,
)
_HE_SO = {
    "triệu": Decimal(10) ** 6,
    "tr": Decimal(10) ** 6,
    "tỷ": Decimal(10) ** 9,
    "tỉ": Decimal(10) ** 9,
    "nghìn": Decimal(1000),
    "ngàn": Decimal(1000),
    "k": Decimal(1000),
}
_DON_VI_TIEN = frozenset({"đồng", "đ", "vnđ", "vnd"})


@dataclass(frozen=True)
class _So:
    gia_tri: Decimal
    loai: str  # "tien" | "phan_tram" | "so"
    phan_cach: bool  # viết có dấu ngăn hàng nghìn ("2.350.000") — gần như chắc chắn là số tiền


def _doc_so(chuoi: str) -> tuple[Decimal | None, bool]:
    """Đọc số theo thói quen Việt Nam: dấu chấm ngăn nghìn, dấu phẩy thập phân.

    Vẫn đọc đúng số kiểu máy ("12.5", "0.125", "2,350,000") vì dữ kiện JSON có thể chứa số thô.
    """
    if re.fullmatch(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?", chuoi) and not chuoi.startswith("0."):
        chuan, phan_cach = chuoi.replace(".", "").replace(",", "."), True
    elif re.fullmatch(r"\d{1,3}(?:,\d{3}){2,}(?:\.\d+)?", chuoi):
        chuan, phan_cach = chuoi.replace(",", ""), True
    else:
        chuan, phan_cach = chuoi.replace(",", "."), False
    try:
        return Decimal(chuan), phan_cach
    except InvalidOperation:
        return None, False


def _trich_so(text: str) -> list[_So]:
    ket_qua: list[_So] = []
    for khop in _SO.finditer(text):
        gia_tri, phan_cach = _doc_so(khop.group("so"))
        if gia_tri is None:
            continue
        don_vi = " ".join((khop.group("don_vi") or "").lower().split())
        if don_vi in ("%", "phần trăm"):
            loai = "phan_tram"
        elif don_vi in _HE_SO:
            gia_tri, loai = gia_tri * _HE_SO[don_vi], "tien"
        elif don_vi in _DON_VI_TIEN:
            loai = "tien"
        else:
            loai = "so"
        ket_qua.append(_So(gia_tri.normalize(), loai, phan_cach))
    return ket_qua


def numbers_in(text: str) -> set[Decimal]:
    """Các con số trong câu sau khi chuẩn hóa: "2.350.000 đ" = "2,35 triệu" = Decimal(2350000).

    Bỏ qua ngày, giờ, mã nghiệp vụ và số nguyên ≤ 31 đứng một mình ("12 tháng", "kỳ 3") — các số đó
    không phải số liệu tài chính và so chúng chỉ sinh báo động giả.
    """
    return {
        so.gia_tri
        for so in _trich_so(text)
        if not (so.loai == "so" and so.gia_tri <= 31 and so.gia_tri == so.gia_tri.to_integral_value())
    }


def _so_lieu_bi_bia(reply: str, fact_texts: Sequence[str]) -> bool:
    """Có số tiền/phần trăm nào trong câu trả lời không xuất hiện trong dữ kiện không?

    Chỉ kiểm số tiền, phần trăm và số viết có dấu ngăn nghìn; năm ("2026") hay số đếm không bị kiểm vì
    dễ báo động giả mà không phải nghĩa vụ "số liệu chính xác". Phía dữ kiện lấy mọi số, kể cả số nhỏ.
    """
    can_kiem = {so.gia_tri for so in _trich_so(reply) if so.loai in ("tien", "phan_tram") or so.phan_cach}
    if not can_kiem:
        return False
    co_trong_du_kien = {so.gia_tri for van_ban in fact_texts for so in _trich_so(van_ban)}
    return not can_kiem <= co_trong_du_kien


# ── Trích dẫn pháp lý ──────────────────────────────────────────────────────────────────────────

# Không có "Quyết định": kho kiến thức (Task 8) cố ý nêu "Quyết định 2866/QĐ-NHNN" trong một đoạn
# CHƯA XÁC MINH để nói rõ nguồn chưa kiểm; chặn chữ đó sẽ làm hỏng chính câu cảnh báo.
_TRICH_DAN = re.compile(
    r"[Đđ]iều\s+\d+"
    r"|[Nn]ghị\s+định"
    r"|[Tt]hông\s+tư"
    r"|\d+/\d{4}/[A-ZĐ]{2,}[\w-]*"
    r"|\bNĐ-CP\b"
    r"|\bBLDS\b"
    r"|[Bb]ộ\s+luật\s+[Dd]ân\s+sự"
    r"|Bộ\s+luật\s+\w+"
    r"|Luật\s+\w+"
)


# Tên luật chấm điểm nội bộ ("R1", "CAPACITY_INSTALLMENT_BURDEN") có số hoặc gạch dưới; viết tắt tên
# văn bản thật ("Luật TCTD") thì không, nên vẫn bị bắt.
_MA_LUAT_NOI_BO = re.compile(r"[A-Z][A-Z0-9]*(?:[0-9_][A-Z0-9_]*)")


def _la_ten_van_ban(cum: str) -> bool:
    """'Luật X'/'Bộ luật X' chỉ là tên văn bản khi X viết hoa hoặc là số.

    FINORA gọi các luật chấm điểm là "bộ luật"/"luật" ("Luật chấm điểm của FINORA…", "Luật R1 trừ 10
    điểm", "Bộ luật FINORA"), nên không thể coi mọi chữ "Luật" đầu câu là trích dẫn pháp luật.
    """
    if cum.startswith(("Luật", "Bộ luật")) and "dân sự" not in cum.lower():
        tu_sau = cum.split()[-1]
        if tu_sau == "FINORA" or _MA_LUAT_NOI_BO.fullmatch(tu_sau):
            return False
        return tu_sau[:1].isupper() or tu_sau[:1].isdigit()
    return True


def _dau_hieu(text: str) -> tuple[set[str], set[str]]:
    chuan = bo_dau(text)
    return set(re.findall(r"[a-z]+", chuan)), set(re.findall(r"\d+", chuan))


def _trich_dan_khong_co_can_cu(reply: str, citations: Sequence[Citation]) -> bool:
    """Câu trả lời nhắc văn bản/điều luật mà căn cứ của lượt không có → LLM tự viết, coi là vi phạm.

    Một cụm hợp lệ khi có ít nhất một căn cứ LUAT chứa đủ các chữ và các số của cụm đó, ví dụ
    "Điều 11" khớp nhãn "Nghị định 94/2025/NĐ-CP, Điều 11 khoản 1 điểm c", còn "Điều 12" thì không.
    """
    nhan_luat = [_dau_hieu(c.label) for c in citations if c.kind == "LUAT"]
    for khop in _TRICH_DAN.finditer(reply):
        cum = khop.group(0)
        if not _la_ten_van_ban(cum):
            continue
        chu, so = _dau_hieu(cum)
        if not any(chu <= chu_nhan and so <= so_nhan for chu_nhan, so_nhan in nhan_luat):
            return True
    return False


# ── Kiểm câu trả lời ───────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ReplyCheck:
    ok: bool
    reasons: tuple[str, ...]  # ⊆ {"PII", "SO_LIEU", "TRICH_DAN", "KHUYEN_NGHI"}


def check_reply(
    reply: str, *, fact_texts: Sequence[str], citations: Sequence[Citation], investor: bool
) -> ReplyCheck:
    """Kiểm câu trả lời của LLM trước khi tới người dùng; có lý do nào thì orchestrator dùng câu mẫu."""
    ly_do: list[str] = []
    if _tim_pii(reply):
        ly_do.append("PII")
    if _so_lieu_bi_bia(reply, fact_texts):
        ly_do.append("SO_LIEU")
    if _trich_dan_khong_co_can_cu(reply, citations):
        ly_do.append("TRICH_DAN")
    if investor and _co_khuyen_nghi(reply):
        ly_do.append("KHUYEN_NGHI")
    return ReplyCheck(ok=not ly_do, reasons=tuple(ly_do))
