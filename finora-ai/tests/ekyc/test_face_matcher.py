from unittest.mock import patch

import numpy as np
import pytest

from app.ml.ekyc.face_matcher import FaceMatcher


@pytest.fixture
def matcher():
    return FaceMatcher()


def _make_dummy_image_bytes() -> bytes:
    """Tạo ảnh JPEG giả để test."""
    import cv2
    img = np.zeros((100, 100, 3), dtype=np.uint8)
    _, buf = cv2.imencode(".jpg", img)
    return buf.tobytes()


@patch("app.ml.ekyc.face_matcher.DeepFace")
def test_face_match_success(mock_deepface, matcher):
    mock_deepface.verify.return_value = {
        "verified": True,
        "distance": 0.25,
        "threshold": 0.4,
        "model": "VGG-Face",
        "similarity_metric": "cosine",
    }

    selfie = _make_dummy_image_bytes()
    cccd = _make_dummy_image_bytes()
    result = matcher.compare(selfie, cccd)

    assert result["match"] is True
    assert result["similarity"] > 0.5
    assert "threshold" in result


@patch("app.ml.ekyc.face_matcher.DeepFace")
def test_face_no_match(mock_deepface, matcher):
    mock_deepface.verify.return_value = {
        "verified": False,
        "distance": 0.8,
        "threshold": 0.4,
        "model": "VGG-Face",
        "similarity_metric": "cosine",
    }

    selfie = _make_dummy_image_bytes()
    cccd = _make_dummy_image_bytes()
    result = matcher.compare(selfie, cccd)

    assert result["match"] is False
    assert result["similarity"] < 0.5


@patch("app.ml.ekyc.face_matcher.DeepFace")
def test_face_match_error_returns_fail(mock_deepface, matcher):
    mock_deepface.verify.side_effect = ValueError("Face not detected")

    selfie = _make_dummy_image_bytes()
    cccd = _make_dummy_image_bytes()
    result = matcher.compare(selfie, cccd)

    assert result["match"] is False
    assert result["similarity"] == 0.0
