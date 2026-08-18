import pytest
import numpy as np
import cv2
from app.ml.liveness_detector import LivenessDetector


@pytest.fixture
def detector():
    return LivenessDetector()


def _make_natural_image() -> bytes:
    """Ảnh có texture tự nhiên (noise) — mô phỏng ảnh thật."""
    rng = np.random.RandomState(42)
    img = rng.randint(0, 256, (200, 200, 3), dtype=np.uint8)
    # Thêm Gaussian blur nhẹ cho tự nhiên hơn
    img = cv2.GaussianBlur(img, (3, 3), 0)
    _, buf = cv2.imencode(".jpg", img)
    return buf.tobytes()


def _make_flat_image() -> bytes:
    """Ảnh phẳng (ít texture) — mô phỏng ảnh từ màn hình."""
    # Ảnh hoàn toàn đồng màu — LBP variance rất thấp
    img = np.full((200, 200, 3), 128, dtype=np.uint8)
    # Dùng PNG để tránh JPEG compression artifacts thêm texture giả
    _, buf = cv2.imencode(".png", img)
    return buf.tobytes()


def test_liveness_natural_image(detector):
    result = detector.check(_make_natural_image())
    assert result["is_live"] is True
    assert result["confidence"] > 0.5
    assert result["method"] == "lbp_texture"


def test_liveness_flat_image(detector):
    result = detector.check(_make_flat_image())
    assert result["is_live"] is False
    assert result["method"] == "lbp_texture"


def test_liveness_invalid_image(detector):
    result = detector.check(b"not an image")
    assert result["is_live"] is False
    assert result["confidence"] == 0.0
