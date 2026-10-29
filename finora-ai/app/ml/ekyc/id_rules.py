"""Luật cấu trúc số định danh cá nhân 12 số — chỉ để **kiểm nhất quán**.

Cấu trúc (TT 07/2016/TT-BCA, sau là TT 59/2021/TT-BCA; cả hai hết hiệu lực từ 1/7/2024
nhưng số đã cấp giữ nguyên):

- 3 số đầu: mã tỉnh nơi đăng ký khai sinh (63 mã, xem ``data/province_codes.json``);
- số thứ 4: thế kỷ + giới tính — 0/1: 1900–1999 nam/nữ; 2/3: 2000–2099; 4/5: 2100…;
- số thứ 5–6: hai số cuối năm sinh; 6 số còn lại ngẫu nhiên.

Lệch cấu trúc **không** phải bằng chứng giả mạo (có số cũ cấp sai cấu trúc, được huỷ và
xác lập lại), nên chỉ đẩy hồ sơ về duyệt tay — không bao giờ RETAKE hay từ chối.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import date
from functools import lru_cache
from pathlib import Path

from app.ml.ekyc.vn_text import GENDER_FEMALE, GENDER_MALE

WARN_PROVINCE_UNKNOWN = "ID_PROVINCE_UNKNOWN"
WARN_STRUCTURE_MISMATCH = "ID_STRUCTURE_MISMATCH"

RE_ID12 = re.compile(r"^\d{12}$")
_PROVINCE_FILE = Path(__file__).parent / "data" / "province_codes.json"
#: Hạn thẻ rơi đúng ngày sinh ở các mốc tuổi đổi thẻ (14 chỉ có từ thẻ Căn cước 2024).
EXPIRY_MILESTONE_AGES = (14, 25, 40, 60)


@lru_cache(maxsize=1)
def province_codes() -> frozenset[str]:
    """Tập 63 mã tỉnh hợp lệ."""
    data = json.loads(_PROVINCE_FILE.read_text(encoding="utf-8"))
    return frozenset(data["codes"])


@dataclass(frozen=True)
class IdStructure:
    """Thông tin suy ra từ số định danh."""

    province_code: str
    century_base: int
    gender: str
    birth_year: int


def decode_id(id_number: str) -> IdStructure | None:
    """Giải cấu trúc số định danh; không đúng 12 chữ số thì ``None``."""
    if not RE_ID12.match(id_number or ""):
        return None
    century_digit = int(id_number[3])
    century_base = 1900 + (century_digit // 2) * 100
    return IdStructure(
        province_code=id_number[:3],
        century_base=century_base,
        gender=GENDER_MALE if century_digit % 2 == 0 else GENDER_FEMALE,
        birth_year=century_base + int(id_number[4:6]),
    )


@dataclass(frozen=True)
class IdRuleResult:
    """Kết quả kiểm luật số. ``ok`` = đúng định dạng và không lệch ngày sinh/giới tính."""

    format_ok: bool
    province_known: bool
    structure: IdStructure | None
    warnings: tuple[str, ...]

    @property
    def ok(self) -> bool:
        return self.format_ok and WARN_STRUCTURE_MISMATCH not in self.warnings


def check_id(
    id_number: str | None, dob: date | None, gender: str | None
) -> IdRuleResult:
    """Đối chiếu số định danh với ngày sinh và giới tính đã đọc từ nguồn khác."""
    structure = decode_id(id_number or "")
    if structure is None:
        return IdRuleResult(
            format_ok=False, province_known=False, structure=None, warnings=()
        )
    warnings: list[str] = []
    province_known = structure.province_code in province_codes()
    if not province_known:
        warnings.append(WARN_PROVINCE_UNKNOWN)
    year_mismatch = dob is not None and dob.year != structure.birth_year
    gender_mismatch = gender is not None and gender != structure.gender
    if year_mismatch or gender_mismatch:
        warnings.append(WARN_STRUCTURE_MISMATCH)
    return IdRuleResult(
        format_ok=True,
        province_known=province_known,
        structure=structure,
        warnings=tuple(warnings),
    )


def full_year(yy: int, id_number: str | None, today: date | None = None) -> int:
    """Năm sinh đủ 4 số từ YY của MRZ: thế kỷ lấy từ số thứ 4 của số định danh.

    Không có số định danh thì dùng mốc xoay: YY lớn hơn năm hiện tại → thế kỷ trước.
    """
    structure = decode_id(id_number or "")
    if structure is not None:
        return structure.century_base + yy
    current = (today or date.today()).year
    candidate = (current // 100) * 100 + yy
    return candidate - 100 if candidate > current else candidate


def expiry_milestone_ok(dob: date | None, expiry: date | None) -> bool:
    """Hạn thẻ có rơi đúng ngày sinh ở một mốc tuổi đổi thẻ không.

    Dùng để kiểm hạn thẻ do AI đọc (không có check digit như MRZ). 29/2 được chấp nhận
    lệch sang 28/2 hoặc 1/3 ở năm không nhuận.
    """
    if dob is None or expiry is None:
        return False
    if expiry.year - dob.year not in EXPIRY_MILESTONE_AGES:
        return False
    if (expiry.month, expiry.day) == (dob.month, dob.day):
        return True
    return (dob.month, dob.day) == (2, 29) and (expiry.month, expiry.day) in (
        (2, 28),
        (3, 1),
    )
