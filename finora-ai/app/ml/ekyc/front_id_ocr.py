"""Gắn mặt trước với mặt sau: tìm **số định danh đã biết** trong chữ số in ở mặt trước.

Vì sao cần: QR (thẻ 2024) và MRZ đều nằm ở mặt sau. Không gắn hai mặt thì kẻ gian ghép
mặt trước thẻ của mình (chân dung khớp selfie) với mặt sau thẻ của nạn nhân.

Đối chiếu một giá trị đã biết dễ và chắc hơn nhiều so với đọc mở: Tesseract chỉ đọc
chữ số (whitelist ``0-9``) trên 65% bên phải mặt trước (bỏ vùng chân dung), rồi tìm
một cửa sổ cách số đã biết ≤ ``max_edits`` phép sửa (thay/thêm/bớt một chữ số). Xác suất
một chuỗi 12 số ngẫu nhiên lọt vào bán kính 1 của số đã biết cỡ 10⁻¹⁰ nên sai lệch một
ký tự không làm yếu phép gắn.
"""

from __future__ import annotations

import re
from collections.abc import Iterable

import cv2
from numpy.typing import NDArray

from app.ml.ekyc import image_io, tesseract_ocr

#: Bỏ 35% bên trái (chân dung, quốc huy) — số định danh in ở phần còn lại.
RIGHT_PART_START = 0.35
_DIGITS_CONFIG = "--oem 1 --psm {psm} -c tessedit_char_whitelist=0123456789"


def edit_distance(a: str, b: str, limit: int) -> int:
    """Levenshtein có cắt sớm: trả ``limit + 1`` ngay khi chắc chắn vượt ``limit``."""
    if abs(len(a) - len(b)) > limit:
        return limit + 1
    previous = list(range(len(b) + 1))
    for i, ch_a in enumerate(a, start=1):
        current = [i] + [0] * len(b)
        for j, ch_b in enumerate(b, start=1):
            current[j] = min(
                previous[j] + 1,
                current[j - 1] + 1,
                previous[j - 1] + (ch_a != ch_b),
            )
        if min(current) > limit:
            return limit + 1
        previous = current
    return previous[-1]


def find_known_id(lines: Iterable[str], known_id: str, max_edits: int = 1) -> bool:
    """Có cửa sổ chữ số nào trong ``lines`` cách ``known_id`` ≤ ``max_edits`` không."""
    target = len(known_id)
    for line in lines:
        digits = re.sub(r"\D", "", line)
        for length in range(max(1, target - max_edits), target + max_edits + 1):
            for start in range(len(digits) - length + 1):
                window = digits[start : start + length]
                if edit_distance(window, known_id, max_edits) <= max_edits:
                    return True
    return False


def read_front_digits(front_card: NDArray, timeout_s: float) -> list[str] | None:
    """Các dòng chữ số Tesseract đọc được ở phần phải mặt trước; ``None`` nếu không có Tesseract."""
    if not tesseract_ocr.available():
        return None
    width = front_card.shape[1]
    region = image_io.to_gray(front_card[:, int(width * RIGHT_PART_START) :])
    region = cv2.resize(region, None, fx=1.5, fy=1.5, interpolation=cv2.INTER_CUBIC)
    _, binary = cv2.threshold(
        cv2.GaussianBlur(region, (3, 3), 0), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU
    )
    lines: list[str] = []
    for image, psm in ((binary, 6), (region, 11)):
        text = tesseract_ocr.image_to_text(
            image, _DIGITS_CONFIG.format(psm=psm), timeout_s
        )
        if text:
            lines.extend(text.splitlines())
    return lines


def front_shows_id(
    front_card: NDArray, known_id: str, max_edits: int, timeout_s: float
) -> bool | None:
    """``True``/``False`` = đã kiểm; ``None`` = không kiểm được (thiếu Tesseract)."""
    lines = read_front_digits(front_card, timeout_s)
    if lines is None:
        return None
    if find_known_id(lines, known_id, max_edits):
        return True
    # Thẻ có thể bị chụp ngược — thử bản xoay 180°.
    rotated = read_front_digits(cv2.rotate(front_card, cv2.ROTATE_180), timeout_s) or []
    return find_known_id(rotated, known_id, max_edits)
