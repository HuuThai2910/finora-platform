"""Dữ liệu tổng hợp cho test eKYC — **không** có thẻ thật hay khuôn mặt thật nào.

Thẻ giả vẽ bằng OpenCV (chữ, khung chân dung xám, QR sinh bằng zxing-cpp), MRZ dùng
mẫu tổng hợp đã tự tính check digit.
"""

from __future__ import annotations

import base64
import os
import tempfile
from collections.abc import Callable

import cv2
import numpy as np
import pytest
import zxingcpp

SYNTHETIC_ID = "001095012345"
SYNTHETIC_QR = (
    "001095012345|123456789|Nguyễn Văn A|15031995|Nam|"
    "Số 1 phố Hàng Bạc, phường Hoàn Kiếm, Hà Nội|01072021"
)
MRZ_SYNTHETIC = (
    "IDVNM0950123455001095012345<<6",
    "9503157M3503155VNM<<<<<<<<<<<2",
    "NGUYEN<<VAN<A<<<<<<<<<<<<<<<<<",
)
#: Mẫu MRZ thật công khai (repo VN_ID) — chỉ dùng để kiểm check digit.
MRZ_REAL_SAMPLE = (
    "IDVNM0690052512046069005251<<0",
    "6904281M2904283VNM<<<<<<<<<<<4",
)

CARD_W, CARD_H = 1400, 883
CARD_COLOR = (225, 230, 235)


def qr_image(text: str, scale: int = 4) -> np.ndarray:
    """Ảnh xám của mã QR mang ``text`` (UTF-8)."""
    barcode = zxingcpp.create_barcode(text, zxingcpp.BarcodeFormat.QRCode)
    return np.array(zxingcpp.write_barcode_to_image(barcode, scale=scale))


def _text_lines(card: np.ndarray, x: int, y0: int, count: int, seed: int) -> None:
    rng = np.random.default_rng(seed)
    words = ["CONG", "HOA", "XA", "HOI", "CHU", "NGHIA", "VIET", "NAM", "DOC", "LAP"]
    for i in range(count):
        line = " ".join(rng.choice(words, size=4 + i % 3))
        cv2.putText(
            card,
            line,
            (x, y0 + i * 52),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.9,
            (40, 40, 40),
            2,
            cv2.LINE_AA,
        )


def front_card(qr_text: str | None = None, seed: int = 1) -> np.ndarray:
    """Mặt trước giả: khung chân dung xám bên trái, chữ bên phải, QR góc trên phải."""
    card = np.full((CARD_H, CARD_W, 3), CARD_COLOR, np.uint8)
    _text_lines(card, 560, 300, 9, seed)
    cv2.rectangle(card, (60, 250), (420, 700), (150, 150, 150), -1)
    if qr_text:
        qr = qr_image(qr_text)
        qh, qw = qr.shape
        card[40 : 40 + qh, CARD_W - 40 - qw : CARD_W - 40] = cv2.cvtColor(
            qr, cv2.COLOR_GRAY2BGR
        )
    return card


def back_card(
    mrz_lines: tuple[str, ...] | None = None, qr_text: str | None = None, seed: int = 2
) -> np.ndarray:
    """Mặt sau giả: chữ phía trên, 3 dòng MRZ ở dải dưới, QR tuỳ chọn (thẻ 2024)."""
    card = np.full((CARD_H, CARD_W, 3), CARD_COLOR, np.uint8)
    _text_lines(card, 80, 90, 6, seed)
    for i, line in enumerate(mrz_lines or ()):
        cv2.putText(
            card,
            line,
            (60, 650 + i * 70),
            cv2.FONT_HERSHEY_SIMPLEX,
            1.55,
            (20, 20, 20),
            3,
            cv2.LINE_AA,
        )
    if qr_text:
        qr = qr_image(qr_text)
        qh, qw = qr.shape
        card[40 : 40 + qh, CARD_W - 40 - qw : CARD_W - 40] = cv2.cvtColor(
            qr, cv2.COLOR_GRAY2BGR
        )
    return card


def on_background(
    card: np.ndarray,
    angle: float = 12.0,
    scale: float = 0.85,
    size: tuple[int, int] = (1600, 1200),
    seed: int = 0,
) -> np.ndarray:
    """Đặt thẻ xoay ``angle`` độ lên nền tối có nhiễu (giống ảnh chụp trên mặt bàn)."""
    width, height = size
    rng = np.random.default_rng(seed)
    background = rng.integers(0, 60, (height, width, 3)).astype(np.uint8)
    h, w = card.shape[:2]
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), angle, scale)
    matrix[0, 2] += width / 2 - w / 2
    matrix[1, 2] += height / 2 - h / 2
    moved = cv2.warpAffine(card, matrix, (width, height))
    mask = cv2.warpAffine(np.full((h, w), 255, np.uint8), matrix, (width, height))
    return np.where(mask[..., None] > 0, moved, background).astype(np.uint8)


def jpeg_bytes(image: np.ndarray, quality: int = 92) -> bytes:
    ok, buffer = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, quality])
    assert ok
    return buffer.tobytes()


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def write_video(
    frame_at: Callable[[int], np.ndarray],
    frames: int,
    fps: float,
    size: tuple[int, int] = (640, 480),
) -> bytes:
    """Ghi video mp4v bằng ``cv2.VideoWriter`` (file tạm) rồi trả bytes."""
    fd, path = tempfile.mkstemp(suffix=".mp4")
    os.close(fd)
    try:
        writer = cv2.VideoWriter(path, cv2.VideoWriter_fourcc(*"mp4v"), fps, size)
        assert writer.isOpened(), "VideoWriter không mở được"
        for index in range(frames):
            writer.write(frame_at(index))
        writer.release()
        with open(path, "rb") as handle:
            return handle.read()
    finally:
        os.remove(path)


def moving_blob_frame(index: int, size: tuple[int, int] = (640, 480)) -> np.ndarray:
    """Khung không có mặt người: nền màu + hình tròn chạy ngang."""
    width, height = size
    image = np.zeros((height, width, 3), np.uint8)
    image[:] = (40 + index % 50, 90, 140)
    cv2.circle(
        image, (50 + (index * 7) % (width - 100), height // 2), 40, (200, 200, 50), -1
    )
    return image


def real_face_image_or_skip() -> np.ndarray:
    """Ảnh mặt thật lấy từ ``EKYC_TEST_FACE_IMAGE`` (không có trong repo); thiếu thì skip."""
    path = os.getenv("EKYC_TEST_FACE_IMAGE", "")
    if not path or not os.path.isfile(path):
        pytest.skip(
            "Đặt EKYC_TEST_FACE_IMAGE=<ảnh chân dung> để chạy test với mặt thật"
        )
    image = cv2.imread(path)
    assert image is not None, "không đọc được EKYC_TEST_FACE_IMAGE"
    return image


def card_with_face(face_image: np.ndarray) -> np.ndarray:
    """Dán ảnh mặt vào khung chân dung (360×450 px) của thẻ tổng hợp."""
    card = front_card(None)
    card[250:700, 60:420] = cv2.resize(
        face_image, (360, 450), interpolation=cv2.INTER_AREA
    )
    return card


def face_crop(engine, image: np.ndarray, margin: float = 0.5) -> np.ndarray:
    """Cắt quanh khuôn mặt lớn nhất (như ảnh thẻ) để mặt chiếm phần lớn khung."""
    faces = engine.detect(image, 0.6)
    assert faces, "ảnh EKYC_TEST_FACE_IMAGE không có khuôn mặt"
    box = faces[0]
    height, width = image.shape[:2]
    x0, y0 = max(0, int(box.x - margin * box.w)), max(0, int(box.y - margin * box.h))
    x1 = min(width, int(box.x + box.w * (1 + margin)))
    y1 = min(height, int(box.y + box.h * (1 + margin)))
    return image[y0:y1, x0:x1].copy()
