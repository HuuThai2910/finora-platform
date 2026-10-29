"""Tìm tấm thẻ trong ảnh và nắn phẳng về tỉ lệ ISO/IEC 7810 ID-1 (85,6 × 54 mm).

Cách làm: contour lớn nhất có ~4 đỉnh (thử cả bản đồ cạnh Canny lẫn ngưỡng Otsu hai
chiều vì nền có thể sáng hơn hoặc tối hơn thẻ), diện tích ≥ ``card_min_area_ratio`` ảnh,
tỉ lệ cạnh hợp lý → ``warpPerspective``. Thẻ có góc bo tròn nên ``approxPolyDP`` đôi khi
ra 5–8 đỉnh; khi đó dùng hình chữ nhật xoay bao quanh nếu nó khít với contour.

Không tìm được thẻ là chuyện bình thường (mobile đã cắt ảnh theo khung ngắm, mép thẻ
trùng mép ảnh): khi đó dùng nguyên ảnh và báo ``card_found=False``.

Thẻ nằm dọc trong ảnh thì xoay thứ tự góc để ảnh ra luôn nằm ngang. Không biết được
xoay 90° theo chiều nào, nên bước sau (chân dung, MRZ) còn thử thêm bản xoay 180°.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np
from numpy.typing import NDArray

from app.ml.ekyc import image_io

CARD_ASPECT = 85.6 / 54.0
#: Ảnh dò thẻ được thu về rộng tối đa mức này cho nhanh; góc tìm được nhân ngược lại.
_DETECT_MAX_WIDTH = 900
#: Tỉ lệ cạnh dài/cạnh ngắn chấp nhận được (thẻ thật 1,585; nới cho phối cảnh).
_ASPECT_MIN, _ASPECT_MAX = 1.25, 1.95
#: Contour chiếm gần hết ảnh là mép ảnh, không phải mép thẻ.
_MAX_AREA_RATIO = 0.97
#: Hình chữ nhật xoay chỉ thay được contour khi khít tới mức này.
_MIN_RECT_FILL = 0.85


@dataclass(frozen=True)
class CardWarp:
    """Kết quả nắn thẻ. ``image`` là thẻ đã nắn, hoặc nguyên ảnh nếu không thấy thẻ."""

    image: NDArray[np.uint8]
    card_found: bool
    corners: NDArray[np.float32] | None = None


def order_corners(points: NDArray) -> NDArray[np.float32]:
    """Sắp 4 góc theo chiều kim đồng hồ bắt đầu từ góc trên-trái (TL, TR, BR, BL)."""
    pts = np.asarray(points, dtype=np.float32).reshape(4, 2)
    center = pts.mean(axis=0)
    angles = np.arctan2(pts[:, 1] - center[1], pts[:, 0] - center[0])
    clockwise = pts[np.argsort(angles)]
    start = int(np.argmin(clockwise.sum(axis=1)))
    return np.roll(clockwise, -start, axis=0)


def _side_lengths(quad: NDArray) -> tuple[float, float]:
    """Trung bình cạnh ngang và cạnh dọc của tứ giác đã sắp góc."""
    tl, tr, br, bl = quad
    horizontal = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) / 2.0
    vertical = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) / 2.0
    return float(horizontal), float(vertical)


def _quad_from_contour(contour: NDArray) -> NDArray | None:
    hull = cv2.convexHull(contour)
    perimeter = cv2.arcLength(hull, True)
    for epsilon in (0.02, 0.03, 0.05):
        approx = cv2.approxPolyDP(hull, epsilon * perimeter, True)
        if len(approx) == 4:
            return approx.reshape(4, 2).astype(np.float32)
    rect = cv2.minAreaRect(hull)
    rect_area = rect[1][0] * rect[1][1]
    if rect_area > 0 and cv2.contourArea(hull) / rect_area >= _MIN_RECT_FILL:
        return cv2.boxPoints(rect).astype(np.float32)
    return None


def _candidate_contours(gray: NDArray) -> list[NDArray]:
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    median = float(np.median(blurred))
    edges = cv2.Canny(
        blurred, int(max(0.0, 0.66 * median)), int(min(255.0, 1.33 * median) or 1)
    )
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=2)
    contours = list(
        cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]
    )

    _, mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    kernel = np.ones((7, 7), np.uint8)
    for binary in (mask, cv2.bitwise_not(mask)):
        closed = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel, iterations=2)
        contours.extend(
            cv2.findContours(closed, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]
        )
    return contours


def find_card_quad(image: NDArray, min_area_ratio: float) -> NDArray[np.float32] | None:
    """Tứ giác bao tấm thẻ (toạ độ ảnh gốc, đã sắp TL-TR-BR-BL) hoặc ``None``."""
    h, w = image.shape[:2]
    scale = min(1.0, _DETECT_MAX_WIDTH / float(w))
    small = (
        cv2.resize(
            image,
            (max(1, round(w * scale)), max(1, round(h * scale))),
            interpolation=cv2.INTER_AREA,
        )
        if scale < 1.0
        else image
    )
    gray = image_io.to_gray(small)
    image_area = float(gray.shape[0] * gray.shape[1])

    best: NDArray | None = None
    best_area = 0.0
    for contour in _candidate_contours(gray):
        area = cv2.contourArea(contour)
        if area < min_area_ratio * image_area or area <= best_area:
            continue
        quad = _quad_from_contour(contour)
        if quad is None:
            continue
        quad_area = cv2.contourArea(quad)
        if not (
            min_area_ratio * image_area <= quad_area <= _MAX_AREA_RATIO * image_area
        ):
            continue
        if not cv2.isContourConvex(quad.reshape(-1, 1, 2).astype(np.int32)):
            continue
        ordered = order_corners(quad)
        horizontal, vertical = _side_lengths(ordered)
        short, long = sorted((horizontal, vertical))
        if short <= 0 or not (_ASPECT_MIN <= long / short <= _ASPECT_MAX):
            continue
        best, best_area = ordered, quad_area

    if best is None:
        return None
    return (best / scale).astype(np.float32)


def warp_card(image: NDArray, min_area_ratio: float, width: int) -> CardWarp:
    """Nắn thẻ về ``width`` × ``width/1,585`` px; không thấy thẻ thì đưa nguyên ảnh về ``width``."""
    quad = find_card_quad(image, min_area_ratio)
    if quad is None:
        return CardWarp(
            image=image_io.resize_to_width(image, width, allow_upscale=True),
            card_found=False,
        )
    horizontal, vertical = _side_lengths(quad)
    if vertical > horizontal:
        # Thẻ nằm dọc: lấy cạnh trái làm cạnh trên để ảnh ra nằm ngang.
        quad = np.roll(quad, 1, axis=0)
    height = max(1, round(width / CARD_ASPECT))
    target = np.array(
        [[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]],
        dtype=np.float32,
    )
    matrix = cv2.getPerspectiveTransform(quad.astype(np.float32), target)
    warped = cv2.warpPerspective(
        image,
        matrix,
        (width, height),
        flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_REPLICATE,
    )
    return CardWarp(image=warped, card_found=True, corners=quad)


def rotate_180(image: NDArray) -> NDArray:
    """Xoay 180° — thử khi người dùng cầm ngược thẻ."""
    return cv2.rotate(image, cv2.ROTATE_180)


def aspect_ratio(image: NDArray) -> float:
    """Tỉ lệ rộng/cao (tiện cho test)."""
    h, w = image.shape[:2]
    return w / float(h) if h else math.inf
