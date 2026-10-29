"""Mã QR trên CCCD gắn chip (mặt trước) và thẻ Căn cước 2024 (mặt sau).

Nội dung là chữ UTF-8 thường, ngăn bằng ``|``, **không có chữ ký số**::

    ID12 | CMND9 (có thể rỗng) | Họ tên | ddmmyyyy | Nam/Nữ | Địa chỉ | ddmmyyyy (ngày cấp)

Trường thứ 8 trở đi (thẻ 2024: số đã huỷ, tên cha mẹ…) bỏ qua. QR giúp **đọc đúng**
chứ không chứng minh thẻ thật — ai cũng in được mã cùng khuôn.

Mã hoá: payload là UTF-8 không có ECI, nhiều decoder đoán nhầm sang Big5/Shift-JIS
(hoặc client giải nhầm sang Latin-1/CP1252). Ưu tiên giải **bytes thô** bằng UTF-8; nếu
chỉ có chuỗi đã giải thì sửa **từng trường** bằng cách mã hoá ngược rồi giải UTF-8 chặt.
Sửa không được thì bỏ tên/địa chỉ từ QR (số, ngày, giới tính vẫn dùng được).
"""

from __future__ import annotations

import logging
import re
import unicodedata
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date

import cv2
import zxingcpp
from numpy.typing import NDArray

from app.ml.ekyc import image_io
from app.ml.ekyc.id_rules import RE_ID12
from app.ml.ekyc.vn_text import (
    clean_display_text,
    normalize_gender,
    parse_compact_date,
)

logger = logging.getLogger(__name__)

SIDE_FRONT = "FRONT"
SIDE_BACK = "BACK"

_REPAIR_ENCODINGS = ("latin-1", "cp1252", "big5", "shift_jis")
_RE_OLD_ID = re.compile(r"^(\d{9}|\d{12})$")
# Ký tự không bao giờ có trong tên/địa chỉ tiếng Việt: điều khiển C1, CJK, Hangul,
# Katakana nửa độ rộng, dạng toàn độ rộng, ký tự thay thế U+FFFD.
_SUSPICIOUS_RANGES = (
    (0x0080, 0x009F),
    (0x2E80, 0x9FFF),
    (0xAC00, 0xD7AF),
    (0xF900, 0xFAFF),
    (0xFF00, 0xFFEF),
    (0xFFFD, 0xFFFD),
)
# Dấu hiệu UTF-8 bị giải thành Latin-1/CP1252: chữ "byte đầu" (Â…ï) theo sau là ký tự
# của "byte tiếp nối" 0x80–0xBF (ký hiệu, không bao giờ đứng sau chữ cái tiếng Việt).
_LATIN_MOJIBAKE = re.compile(
    "[\u00c2-\u00ef][\u0080-\u00bf\u0152\u0153\u0160\u0161\u0178\u017d"
    "\u017e\u0192\u02c6\u02dc\u2013\u2014\u2018-\u201e\u2020-\u2022\u2026"
    "\u2030\u2039\u203a\u20ac\u2122]"
)


def looks_broken(text: str) -> bool:
    """Chuỗi có dấu hiệu lỗi mã (mojibake) không."""
    for ch in text:
        code = ord(ch)
        if any(low <= code <= high for low, high in _SUSPICIOUS_RANGES):
            return True
    return bool(_LATIN_MOJIBAKE.search(text))


def repair_text(text: str) -> str | None:
    """Sửa chuỗi UTF-8 bị giải nhầm bảng mã; trả ``None`` nếu hỏng mà không sửa được.

    Thử mã hoá ngược bằng từng bảng mã rồi giải UTF-8 **chặt**: chuỗi tiếng Việt đúng
    gần như không thể vừa mã hoá được bằng Latin-1/Big5/Shift-JIS vừa tạo ra UTF-8 hợp
    lệ, nên phép thử này không làm hỏng chuỗi vốn đã đúng.
    """
    text = unicodedata.normalize("NFC", text)
    if text.isascii():
        return text
    for encoding in _REPAIR_ENCODINGS:
        try:
            fixed = text.encode(encoding).decode("utf-8")
        except (UnicodeEncodeError, UnicodeDecodeError):
            continue
        fixed = unicodedata.normalize("NFC", fixed)
        if fixed != text and not looks_broken(fixed):
            return fixed
    return None if looks_broken(text) else text


@dataclass(frozen=True)
class QrPayload:
    """Nội dung QR đã kiểm. Hợp lệ khi có số định danh 12 số và ngày sinh đúng."""

    id_number: str
    old_id_number: str | None
    full_name: str | None
    date_of_birth: date
    gender: str | None
    address: str | None
    issue_date: date | None
    text_dropped: bool


def parse_qr_text(text: str | None) -> QrPayload | None:
    """Parse chuỗi QR CCCD; sai khuôn (thiếu số 12 chữ số / ngày sinh) thì ``None``.

    Chuỗi QR ví Zalo (ngày ``dd/mm/yyyy``, không có trường CMND) không qua được bước
    ngày sinh ``ddmmyyyy`` nên bị loại tự nhiên.
    """
    if not text or "|" not in text:
        return None
    parts = text.strip().split("|")
    if len(parts) < 5:
        return None
    id_number = parts[0].strip()
    birth = parse_compact_date(parts[3])
    if not RE_ID12.match(id_number) or birth is None:
        return None

    old_id = parts[1].strip()
    name = repair_text(parts[2])
    gender_text = repair_text(parts[4])
    address = repair_text(parts[5]) if len(parts) > 5 else None
    dropped = (name is None and parts[2].strip() != "") or (
        len(parts) > 5 and address is None and parts[5].strip() != ""
    )
    name = clean_display_text(name).upper() if name else None
    address = clean_display_text(address) if address else None
    return QrPayload(
        id_number=id_number,
        old_id_number=old_id if _RE_OLD_ID.match(old_id) else None,
        full_name=name or None,
        date_of_birth=birth,
        gender=normalize_gender(gender_text),
        address=address or None,
        issue_date=parse_compact_date(parts[6]) if len(parts) > 6 else None,
        text_dropped=dropped,
    )


def text_from_barcode(raw: bytes | None, decoded: str) -> str:
    """Ưu tiên bytes thô giải UTF-8 chặt; không được thì dùng chuỗi decoder đã giải."""
    if raw:
        try:
            return unicodedata.normalize("NFC", raw.decode("utf-8"))
        except UnicodeDecodeError:
            pass
    return decoded


def _variants(gray: NDArray) -> Iterator[NDArray]:
    """Ảnh gốc → 4 góc phần tư (chồng lấn) → phóng to ×2.

    QR trên thẻ chỉ ~13–18 mm; cắt góc giúp bộ dò bớt nhiễu, phóng to giúp module nhỏ.
    """
    yield gray
    h, w = gray.shape[:2]
    qh, qw = int(h * 0.6), int(w * 0.6)
    quadrants = [
        gray[0:qh, 0:qw],
        gray[0:qh, w - qw : w],
        gray[h - qh : h, 0:qw],
        gray[h - qh : h, w - qw : w],
    ]
    yield from quadrants
    if max(h, w) * 2 <= 4200:
        yield cv2.resize(gray, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
    else:
        for quadrant in quadrants:
            yield cv2.resize(
                quadrant, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC
            )


@dataclass(frozen=True)
class QrRead:
    """QR server tự giải trên một ảnh: ``text`` thô và ``payload`` nếu đúng khuôn CCCD."""

    text: str
    payload: QrPayload | None


def decode_qr(images: list[NDArray]) -> QrRead | None:
    """Giải QR trên các ảnh của **một mặt** (ảnh gốc, thẻ đã nắn…).

    Trả QR đúng khuôn CCCD đầu tiên; không có thì QR bất kỳ đọc được (để biết mặt này
    có QR); không đọc được gì thì ``None``.
    """
    fallback: QrRead | None = None
    for image in images:
        for variant in _variants(image_io.to_gray(image)):
            try:
                barcodes = zxingcpp.read_barcodes(
                    variant, formats=zxingcpp.BarcodeFormat.QRCode
                )
            except Exception as exc:  # noqa: BLE001 — ảnh lạ làm zxing lỗi: bỏ biến thể này
                logger.debug("zxing bỏ qua một biến thể ảnh: %s", type(exc).__name__)
                continue
            for barcode in barcodes:
                text = text_from_barcode(barcode.bytes, barcode.text)
                payload = parse_qr_text(text)
                if payload is not None:
                    return QrRead(text=text, payload=payload)
                if fallback is None and text:
                    fallback = QrRead(text=text, payload=None)
    return fallback
