"""Tiện ích chữ tiếng Việt dùng chung cho QR, MRZ và bước hợp nhất.

Giữ ở một chỗ để mọi nguồn (QR, MRZ, Gemini) được chuẩn hoá cùng một cách trước khi
so khớp — lệch chuẩn hoá giữa các nguồn sẽ thành "mâu thuẫn" giả.
"""

from __future__ import annotations

import re
import unicodedata
from datetime import date

GENDER_MALE = "Nam"
GENDER_FEMALE = "Nữ"

_RE_DIGITS8 = re.compile(r"^\d{8}$")
_RE_DMY = re.compile(r"^(\d{1,2})\s*[/\-.]\s*(\d{1,2})\s*[/\-.]\s*(\d{4})$")


def strip_diacritics(text: str) -> str:
    """Bỏ dấu tiếng Việt. ``Đ/đ`` không tách được bằng NFD nên đổi tay."""
    text = text.replace("Đ", "D").replace("đ", "d")
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")


def normalize_name(name: str) -> str:
    """Tên để so khớp: bỏ dấu, IN HOA, chỉ giữ chữ cái và một khoảng trắng."""
    plain = strip_diacritics(name).upper()
    plain = re.sub(r"[^A-Z]+", " ", plain)
    return " ".join(plain.split())


def clean_display_text(text: str) -> str:
    """Chuẩn hoá NFC và gộp khoảng trắng — dùng cho tên/địa chỉ trả về client."""
    return " ".join(unicodedata.normalize("NFC", text).split())


def parse_compact_date(text: str) -> date | None:
    """``ddmmyyyy`` (định dạng trong QR) → ``date``; sai ngày thì ``None``."""
    text = (text or "").strip()
    if not _RE_DIGITS8.match(text):
        return None
    try:
        return date(int(text[4:8]), int(text[2:4]), int(text[0:2]))
    except ValueError:
        return None


def parse_display_date(text: str | None) -> date | None:
    """``dd/mm/yyyy`` (cũng nhận ``-`` hoặc ``.``) → ``date``; sai thì ``None``."""
    if not text:
        return None
    match = _RE_DMY.match(text.strip())
    if not match:
        return None
    day, month, year = (int(g) for g in match.groups())
    try:
        return date(year, month, day)
    except ValueError:
        return None


def format_date(value: date | None) -> str | None:
    """``date`` → ``dd/mm/yyyy`` (định dạng hợp đồng API)."""
    return value.strftime("%d/%m/%Y") if value else None


def normalize_gender(text: str | None) -> str | None:
    """Mọi cách viết giới tính (Nam/Nữ/NU/M/F/Male/Female) → ``Nam`` | ``Nữ``."""
    if not text:
        return None
    key = strip_diacritics(text).strip().upper()
    if key in ("NAM", "M", "MALE"):
        return GENDER_MALE
    if key in ("NU", "F", "FEMALE"):
        return GENDER_FEMALE
    return None
