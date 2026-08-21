import cv2
import numpy as np
import pytest

from app.ml.ekyc.liveness_detector import LivenessDetector


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


def _naive_lbp(gray):
    """Bản LBP vòng lặp từng pixel — mốc so sánh cho bản vector hoá."""
    h, w = gray.shape
    lbp = np.zeros((h - 2, w - 2), dtype=np.uint8)
    for i in range(1, h - 1):
        for j in range(1, w - 1):
            c = gray[i, j]
            code = 0
            code |= (gray[i - 1, j - 1] >= c) << 7
            code |= (gray[i - 1, j] >= c) << 6
            code |= (gray[i - 1, j + 1] >= c) << 5
            code |= (gray[i, j + 1] >= c) << 4
            code |= (gray[i + 1, j + 1] >= c) << 3
            code |= (gray[i + 1, j] >= c) << 2
            code |= (gray[i + 1, j - 1] >= c) << 1
            code |= (gray[i, j - 1] >= c) << 0
            lbp[i - 1, j - 1] = code
    return lbp


def test_lbp_vector_hoa_cho_ket_qua_trung_voi_vong_lap(detector):
    """Tối ưu tốc độ không được đổi kết quả — nếu đổi thì ngưỡng đã dò mất giá trị."""
    rng = np.random.RandomState(7)
    gray = rng.randint(0, 256, (40, 60), dtype=np.uint8)
    assert np.array_equal(detector._compute_lbp(gray), _naive_lbp(gray))


def test_roi_chi_xet_vung_duoc_chi_dinh(detector):
    """Vùng phẳng và vùng nhiễu trong cùng một ảnh phải cho variance khác hẳn nhau."""
    rng = np.random.RandomState(11)
    image = np.zeros((100, 200, 3), dtype=np.uint8)
    image[:, :100] = 128  # nửa trái phẳng
    image[:, 100:] = rng.randint(0, 256, (100, 100, 3), dtype=np.uint8)  # nửa phải nhiễu

    flat = detector.texture_variance(image, roi=(0, 0, 100, 100))
    noisy = detector.texture_variance(image, roi=(100, 0, 100, 100))
    assert flat < noisy


def test_roi_qua_nho_tra_none(detector):
    image = np.zeros((100, 100, 3), dtype=np.uint8)
    assert detector.texture_variance(image, roi=(0, 0, 2, 2)) is None
