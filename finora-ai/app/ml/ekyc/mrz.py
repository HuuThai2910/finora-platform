"""MRZ mặt sau CCCD gắn chip / thẻ Căn cước: ICAO 9303 TD1, 3 dòng × 30 ký tự.

Bố cục Việt Nam (index 0)::

    L1[0:5]='IDVNM'; L1[5:14]=9 số cuối số định danh; L1[14]=cd(L1[5:14])
    L1[15:27]=số định danh 12 số; L1[27:29]='<<'; L1[29]=cd(L1[15:29])  ← tín hiệu mềm
    L2[0:6]=YYMMDD sinh; L2[6]=cd; L2[7]=M/F; L2[8:14]=YYMMDD hết hạn; L2[14]=cd
    L2[15:18]='VNM'; L2[18:29] đệm; L2[29]=cd(L1[5:30]+L2[0:7]+L2[8:15]+L2[18:29])
    L3 = HO<<TEN<DEM (không dấu)

Check digit ICAO 9303 Part 3 §4.9: trọng số 7-3-1 lặp, mod 10; ``<``=0, A–Z=10–35.
``L1[29]`` không có trong ICAO và chưa có văn bản chính thức (chỉ đúng trên mẫu thật)
nên chỉ báo ``id12_ok``, không quyết định ``valid``.

Sửa lỗi OCR là **ánh xạ tất định** theo loại vị trí: ở trường số, chữ dễ nhầm đổi về
số (O/Q/D/U→0, I/L→1, Z→2, S→5, G→6, B→8); ở trường chữ, số đổi về chữ. Không thử
thay số-bằng-số rồi xem check digit có qua không: thử nhiều phương án với check digit
mod 10 sẽ có xác suất "sửa nhầm mà vẫn qua" đáng kể. Một bộ dòng chỉ được nhận khi
**mọi** check digit bắt buộc đều đúng.
"""

from __future__ import annotations

import logging
import re
import unicodedata
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date

import cv2
import numpy as np
from numpy.typing import NDArray

from app.ml.ekyc import id_rules, image_io, tesseract_ocr
from app.ml.ekyc.vn_text import GENDER_FEMALE, GENDER_MALE

logger = logging.getLogger(__name__)

LINE_LENGTH = 30
DOC_PREFIX = "IDVNM"
NATIONALITY = "VNM"
ENGINE_TESSERACT = "TESSERACT"
ENGINE_GEMINI = "GEMINI"

_TO_DIGIT = str.maketrans(
    {
        "O": "0",
        "Q": "0",
        "D": "0",
        "U": "0",
        "I": "1",
        "L": "1",
        "Z": "2",
        "S": "5",
        "G": "6",
        "B": "8",
    }
)
_TO_ALPHA = str.maketrans(
    {"0": "O", "1": "I", "2": "Z", "4": "A", "5": "S", "6": "G", "8": "B"}
)
_ALLOWED = re.compile(r"[^A-Z0-9<]")
_FILLER_LIKE = re.compile(r"[^0-9<]")
_TESSERACT_CONFIG = (
    "--oem 1 --psm {psm} -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789< "
    "-c load_system_dawg=0 -c load_freq_dawg=0"
)
#: Dải MRZ chiếm khoảng 1/3 dưới của thẻ TD1; lấy dư một chút.
MRZ_BAND_RATIO = 0.35


def _char_value(ch: str) -> int:
    if ch.isdigit():
        return int(ch)
    if ch == "<":
        return 0
    return ord(ch) - 55  # A=10 … Z=35


def check_digit(text: str) -> str:
    """Check digit ICAO 9303 (7-3-1, mod 10)."""
    weights = (7, 3, 1)
    return str(sum(_char_value(ch) * weights[i % 3] for i, ch in enumerate(text)) % 10)


def _check(field: str, digit: str) -> bool:
    if digit == "<":
        digit = "0"
    return digit.isdigit() and check_digit(field) == digit


@dataclass(frozen=True)
class MrzResult:
    """Kết quả parse một bộ 3 dòng MRZ (đã sửa ký tự)."""

    lines: tuple[str, str, str]
    id_number: str | None
    doc_number: str
    birth_date: date | None
    sex: str | None
    expiry_date: date | None
    no_expiry: bool
    surname: str
    given_names: str
    doc_number_ok: bool
    dob_ok: bool
    expiry_ok: bool
    composite_ok: bool
    id12_ok: bool
    layout_ok: bool

    @property
    def valid(self) -> bool:
        """Mọi check digit bắt buộc đúng, bố cục VN đúng, ngày tháng có thật."""
        return (
            self.doc_number_ok
            and self.dob_ok
            and self.expiry_ok
            and self.composite_ok
            and self.layout_ok
            and self.id_number is not None
            and self.birth_date is not None
            and (self.expiry_date is not None or self.no_expiry)
        )

    @property
    def checks_passed(self) -> int:
        """Số kiểm tra đạt — để chọn bộ dòng "gần đúng nhất" khi không bộ nào hợp lệ."""
        return sum(
            (
                self.doc_number_ok,
                self.dob_ok,
                self.expiry_ok,
                self.composite_ok,
                self.layout_ok,
            )
        )

    @property
    def gender(self) -> str | None:
        return {"M": GENDER_MALE, "F": GENDER_FEMALE}.get(self.sex or "")

    @property
    def full_name(self) -> str | None:
        name = " ".join(part for part in (self.surname, self.given_names) if part)
        return name or None


def _clean_line(raw: str) -> str:
    text = unicodedata.normalize("NFKC", raw).upper()
    text = (
        text.replace("«", "<<").replace("‹", "<").replace("〈", "<").replace("＜", "<")
    )
    return _ALLOWED.sub("", re.sub(r"\s+", "", text))


def _fuzzy_equal(text: str, expected: str, min_hits: int) -> bool:
    candidate = text.translate(_TO_ALPHA)
    hits = sum(1 for a, b in zip(candidate, expected) if a == b)
    return len(text) == len(expected) and hits >= min_hits


def _fit_line1(line: str) -> str | None:
    if len(line) == LINE_LENGTH:
        return line
    # Số ký tự đệm '<' hay bị đọc thừa/thiếu: dựng lại với đúng 2 ký tự đệm.
    match = re.match(r"^(.{5})([0-9A-Z]{22})[<KC]*([0-9A-Z])$", line)
    if match:
        return match.group(1) + match.group(2) + "<<" + match.group(3)
    return None


def _fit_line2(line: str) -> str | None:
    if len(line) == LINE_LENGTH:
        return line
    match = re.match(
        r"^([0-9A-Z]{7})([0-9A-Z<])([0-9A-Z<]{7})([0-9A-Z]{3})[<KC]*([0-9A-Z])$", line
    )
    if match:
        return (
            match.group(1)
            + match.group(2)
            + match.group(3)
            + match.group(4)
            + "<" * 11
            + match.group(5)
        )
    return None


def _fit_line3(line: str | None) -> str:
    line = (line or "").translate(_TO_ALPHA)
    line = re.sub(r"[^A-Z<]", "<", line)
    return (line + "<" * LINE_LENGTH)[:LINE_LENGTH]


def _repair_line1(line: str) -> str:
    prefix = DOC_PREFIX if _fuzzy_equal(line[0:5], DOC_PREFIX, 3) else line[0:5]
    numeric = line[5:27].translate(_TO_DIGIT)
    check = line[29].translate(_TO_DIGIT)
    return prefix + numeric + "<<" + check


def _repair_line2(line: str) -> str:
    dob = line[0:7].translate(_TO_DIGIT)
    sex = line[7] if line[7] in "MF<" else "?"
    expiry_field = line[8:14]
    if set(expiry_field) <= set("<KC"):
        expiry_field = "<" * 6
    else:
        expiry_field = expiry_field.translate(_TO_DIGIT)
    expiry_check = line[14] if line[14] == "<" else line[14].translate(_TO_DIGIT)
    nationality = (
        NATIONALITY if _fuzzy_equal(line[15:18], NATIONALITY, 2) else line[15:18]
    )
    optional = _FILLER_LIKE.sub("<", line[18:29])
    composite = line[29].translate(_TO_DIGIT)
    return dob + sex + expiry_field + expiry_check + nationality + optional + composite


def _yymmdd(text: str, year: int | None) -> date | None:
    if year is None or not re.fullmatch(r"\d{6}", text):
        return None
    try:
        return date(year, int(text[2:4]), int(text[4:6]))
    except ValueError:
        return None


def parse_td1(line1: str, line2: str, line3: str | None = None) -> MrzResult | None:
    """Parse bộ 3 dòng thô (đã OCR) sau khi chuẩn hoá độ dài và sửa ký tự nhầm."""
    fitted1 = _fit_line1(_clean_line(line1))
    fitted2 = _fit_line2(_clean_line(line2))
    if fitted1 is None or fitted2 is None:
        return None
    l1 = _repair_line1(fitted1)
    l2 = _repair_line2(fitted2)
    l3 = _fit_line3(_clean_line(line3) if line3 else None)

    id_field = l1[15:27]
    id_number = id_field if id_field.isdigit() else None
    doc_number = l1[5:14]
    dob_field, expiry_field = l2[0:6], l2[8:14]
    no_expiry = expiry_field == "<" * 6

    birth_year = None
    if re.fullmatch(r"\d{6}", dob_field):
        birth_year = id_rules.full_year(int(dob_field[0:2]), id_number)
    birth_date = _yymmdd(dob_field, birth_year)
    expiry_date = None
    if not no_expiry and re.fullmatch(r"\d{6}", expiry_field):
        expiry_date = _yymmdd(expiry_field, 2000 + int(expiry_field[0:2]))

    surname, _, given = l3.strip("<").partition("<<")
    layout_ok = (
        l1[0:5] == DOC_PREFIX
        and l2[15:18] == NATIONALITY
        and id_number is not None
        and doc_number == id_number[3:]
    )
    return MrzResult(
        lines=(l1, l2, l3),
        id_number=id_number,
        doc_number=doc_number,
        birth_date=birth_date,
        sex=l2[7] if l2[7] in "MF" else None,
        expiry_date=expiry_date,
        no_expiry=no_expiry,
        surname=surname.replace("<", " ").strip(),
        given_names=given.replace("<", " ").strip(),
        doc_number_ok=_check(doc_number, l1[14]),
        dob_ok=_check(dob_field, l2[6]),
        expiry_ok=_check(expiry_field, l2[14]),
        composite_ok=_check(l1[5:30] + l2[0:7] + l2[8:15] + l2[18:29], l2[29]),
        id12_ok=_check(l1[15:29], l1[29]),
        layout_ok=layout_ok,
    )


def _looks_like_line1(line: str) -> bool:
    return len(line) >= 25 and _fuzzy_equal(line[0:5], DOC_PREFIX, 3)


def candidate_triples(lines: Iterable[str]) -> list[tuple[str, str, str | None]]:
    """Tìm các bộ (L1, L2, L3?) trong văn bản OCR: L1 bắt đầu giống ``IDVNM``."""
    cleaned = [c for c in (_clean_line(line) for line in lines) if len(c) >= 20]
    triples: list[tuple[str, str, str | None]] = []
    for index, line in enumerate(cleaned):
        if _looks_like_line1(line) and index + 1 < len(cleaned):
            third = cleaned[index + 2] if index + 2 < len(cleaned) else None
            triples.append((line, cleaned[index + 1], third))
    return triples


@dataclass(frozen=True)
class MrzRead:
    """Kết quả đọc MRZ của một engine: ``found`` = thấy dòng giống MRZ."""

    found: bool
    result: MrzResult | None
    engine: str | None

    @property
    def valid(self) -> bool:
        return self.result is not None and self.result.valid


def best_of(triples: Iterable[tuple[str, str, str | None]], engine: str) -> MrzRead:
    """Bộ dòng hợp lệ đầu tiên; không có thì bộ gần đúng nhất (để báo ``MRZ_UNREADABLE``)."""
    found = False
    best: MrzResult | None = None
    for line1, line2, line3 in triples:
        found = True
        parsed = parse_td1(line1, line2, line3)
        if parsed is None:
            continue
        if parsed.valid:
            return MrzRead(found=True, result=parsed, engine=engine)
        if best is None or parsed.checks_passed > best.checks_passed:
            best = parsed
    return MrzRead(found=found, result=best, engine=engine if found else None)


def mrz_band(card: NDArray) -> NDArray:
    """Dải dưới của thẻ đã nắn, nơi có 3 dòng MRZ."""
    height = card.shape[0]
    return card[int(height * (1.0 - MRZ_BAND_RATIO)) :, :]


def _ocr_variants(band: NDArray) -> list[NDArray]:
    gray = image_io.to_gray(band)
    if gray.shape[1] < 2000:
        gray = cv2.resize(gray, None, fx=1.5, fy=1.5, interpolation=cv2.INTER_CUBIC)
    blurred = cv2.GaussianBlur(gray, (3, 3), 0)
    _, otsu = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    adaptive = cv2.adaptiveThreshold(
        blurred, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 41, 15
    )
    return [otsu, adaptive, gray]


def read_with_tesseract(card: NDArray, timeout_s: float) -> MrzRead | None:
    """Đọc MRZ bằng Tesseract trên thẻ đã nắn (thử cả bản xoay 180°).

    Trả ``None`` khi máy không có Tesseract để bên gọi chuyển sang Gemini.
    """
    if not tesseract_ocr.available():
        return None
    overall = MrzRead(found=False, result=None, engine=None)
    for oriented in (card, cv2.rotate(card, cv2.ROTATE_180)):
        band = mrz_band(oriented)
        for variant in _ocr_variants(band):
            for psm in (6, 4):
                text = tesseract_ocr.image_to_text(
                    variant, _TESSERACT_CONFIG.format(psm=psm), timeout_s
                )
                if not text:
                    continue
                read = best_of(candidate_triples(text.splitlines()), ENGINE_TESSERACT)
                if read.valid:
                    return read
                overall = _better(overall, read)
        if overall.found:
            # Đã thấy MRZ ở chiều này — không cần thử chiều ngược.
            break
    return overall


def _better(current: MrzRead, new: MrzRead) -> MrzRead:
    if not new.found:
        return current
    if not current.found or current.result is None:
        return new
    if (
        new.result is not None
        and new.result.checks_passed > current.result.checks_passed
    ):
        return new
    return current


def blank_checks() -> dict:
    """Khối ``checks.mrz`` khi không thấy MRZ."""
    return {
        "found": False,
        "valid": False,
        "engine": None,
        "doc_number_ok": False,
        "dob_ok": False,
        "expiry_ok": False,
        "composite_ok": False,
        "id12_ok": False,
    }


def checks_dict(read: MrzRead) -> dict:
    """Khối ``checks.mrz`` của response."""
    if not read.found:
        return blank_checks()
    result = read.result
    return {
        "found": True,
        "valid": read.valid,
        "engine": read.engine,
        "doc_number_ok": bool(result and result.doc_number_ok),
        "dob_ok": bool(result and result.dob_ok),
        "expiry_ok": bool(result and result.expiry_ok),
        "composite_ok": bool(result and result.composite_ok),
        "id12_ok": bool(result and result.id12_ok),
    }


def to_numpy_band(card: NDArray) -> NDArray:
    """Dải MRZ dạng ảnh liền bộ nhớ (tiện mã hoá JPEG gửi AI)."""
    return np.ascontiguousarray(mrz_band(card))
