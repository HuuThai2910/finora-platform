"""Cổng chất lượng ảnh thẻ: độ nét, loá, độ sáng.

Đo trên thẻ đã nắn và đưa về **cùng một chiều rộng** (mặc định 1000 px), vì phương
sai Laplacian phụ thuộc tỉ lệ ảnh: cùng một tấm thẻ chụp 4000 px và 800 px cho số rất
khác nhau. Ngưỡng là placeholder — hiệu chỉnh bằng ảnh tự chụp.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np
from numpy.typing import NDArray

from app.ml.ekyc import image_io
from app.ml.ekyc.thresholds import DocThresholds

ISSUE_TOO_DARK = "TOO_DARK"
ISSUE_GLARE = "GLARE"
ISSUE_BLUR = "BLUR"

#: Pixel coi là loá khi kênh V (HSV) vượt mức này.
GLARE_V_LEVEL = 250


@dataclass(frozen=True)
class SideQuality:
    """Số đo chất lượng của một mặt thẻ; ``issues`` xếp theo mức nên sửa trước."""

    card_found: bool
    sharpness: float
    glare_ratio: float
    brightness: float
    ok: bool
    issues: tuple[str, ...]

    def as_dict(self) -> dict:
        return {
            "card_found": self.card_found,
            "sharpness": round(self.sharpness, 1),
            "glare_ratio": round(self.glare_ratio, 4),
            "brightness": round(self.brightness, 3),
            "ok": self.ok,
        }


def glare_ratio(image: NDArray) -> float:
    """Tỉ lệ pixel cháy sáng (V > 250)."""
    if image.ndim == 3:
        value = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)[:, :, 2]
    else:
        value = image
    return float(np.count_nonzero(value > GLARE_V_LEVEL)) / float(value.size)


def measure_quality(card: NDArray, card_found: bool, th: DocThresholds) -> SideQuality:
    """Đo chất lượng một mặt thẻ (ảnh đã nắn, hoặc nguyên ảnh nếu không tìm thấy thẻ).

    Thứ tự ``issues`` = thứ tự người dùng nên khắc phục: tối → loá → mờ (ảnh tối thì
    Laplacian thấp theo, nên báo "mờ" khi thật ra thiếu sáng là gây hiểu nhầm).
    """
    normalized = image_io.resize_to_width(card, th.quality_width, allow_upscale=True)
    sharp = image_io.sharpness(normalized)
    glare = glare_ratio(normalized)
    bright = image_io.brightness(normalized)

    issues: list[str] = []
    if bright < th.min_brightness:
        issues.append(ISSUE_TOO_DARK)
    if glare > th.max_glare_ratio:
        issues.append(ISSUE_GLARE)
    if sharp < th.min_sharpness:
        issues.append(ISSUE_BLUR)
    return SideQuality(
        card_found=card_found,
        sharpness=sharp,
        glare_ratio=glare,
        brightness=bright,
        ok=not issues,
        issues=tuple(issues),
    )
