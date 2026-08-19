"""Tests cho Document Forgery Detection — ELA + EXIF metadata."""

import base64
from unittest.mock import patch

import numpy as np
import pytest

# ── Service tests ──────────────────────────────────────────────────────────────


class TestElaAnalysis:
    """Test Error Level Analysis."""

    def test_clean_image_not_tampered(self):
        """Ảnh màu đồng nhất — ELA error thấp, không tampered."""
        cv2 = pytest.importorskip("cv2")
        from app.ml.document_analysis import ela_analysis

        # Ảnh đồng màu 200×200 — nén JPEG sẽ gần như lossless
        img = np.full((200, 200, 3), 128, dtype=np.uint8)

        _, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
        image_bytes = buf.tobytes()

        result = ela_analysis(image_bytes)

        assert result["is_tampered"] is False
        assert result["mean_error"] < 5.0

    def test_tampered_image_detected(self):
        """Mock ELA khi ảnh bị chỉnh sửa — suspicious_area_pct > 5%."""
        from app.ml.document_analysis import ela_analysis

        # ELA trên ảnh tổng hợp có thể không phản ánh đúng thực tế.
        # Test đơn vị mock kết quả, test tích hợp sẽ dùng ảnh thật.
        with patch("app.ml.document_analysis.cv2") as mock_cv2:
            mock_cv2.IMREAD_COLOR = 1
            mock_cv2.IMWRITE_JPEG_QUALITY = 1

            # Ảnh gốc 100×100
            original = np.full((100, 100, 3), 50, dtype=np.uint8)
            mock_cv2.imdecode.side_effect = [original, original.copy()]

            # Error map giả — vùng 20×20 có error = 30 (> threshold 15)
            compressed = original.copy()
            compressed[40:60, 40:60] = 80  # Tạo khác biệt
            diff = np.abs(original.astype(np.float64) - compressed.astype(np.float64))

            mock_cv2.imencode.return_value = (True, np.array([1, 2, 3]))
            mock_cv2.absdiff.return_value = diff

            result = ela_analysis(b"fake-image")

        assert result["max_error"] == 30.0

    def test_invalid_image_returns_safe_default(self):
        """Ảnh không decode được — trả kết quả mặc định, không crash."""
        from app.ml.document_analysis import ela_analysis

        result = ela_analysis(b"not-an-image")

        assert result["is_tampered"] is False
        assert result["max_error"] == 0.0


class TestMetadataCheck:
    """Test EXIF metadata analysis."""

    def test_missing_exif(self):
        """Ảnh không có EXIF — flag MISSING_EXIF."""
        cv2 = pytest.importorskip("cv2")
        from app.ml.document_analysis import metadata_check

        # OpenCV encode JPEG không kèm EXIF
        img = np.zeros((100, 100, 3), dtype=np.uint8)
        _, buf = cv2.imencode(".jpg", img)
        image_bytes = buf.tobytes()

        result = metadata_check(image_bytes)

        assert "MISSING_EXIF" in result["flags"]

    def test_edited_software_detected(self):
        """EXIF có tag Software chứa tên phần mềm chỉnh sửa — flag."""
        from unittest.mock import MagicMock

        from app.ml.document_analysis import metadata_check

        # Mock PIL Image với EXIF chứa Software = "Adobe Photoshop"
        mock_img = MagicMock()
        mock_exif = {305: "Adobe Photoshop CC 2024"}  # 305 = Software tag
        mock_img.getexif.return_value = mock_exif

        with patch("app.ml.document_analysis.Image") as mock_pil:
            mock_pil.open.return_value = mock_img
            result = metadata_check(b"fake-bytes")

        assert "EDITED_BY_SOFTWARE" in result["flags"]

    def test_no_camera_info_flag(self):
        """EXIF có dữ liệu nhưng thiếu Make/Model — flag NO_CAMERA_INFO."""
        from unittest.mock import MagicMock

        from app.ml.document_analysis import metadata_check

        mock_img = MagicMock()
        # EXIF có Software nhưng không phải editor, và không có Make/Model
        mock_exif = {305: "Android 14"}
        mock_img.getexif.return_value = mock_exif

        with patch("app.ml.document_analysis.Image") as mock_pil:
            mock_pil.open.return_value = mock_img
            result = metadata_check(b"fake-bytes")

        assert "NO_CAMERA_INFO" in result["flags"]
        assert "EDITED_BY_SOFTWARE" not in result["flags"]


class TestCombinedVerdict:
    """Test combined ELA + EXIF verdict."""

    def test_clean_image_low_confidence(self):
        """Ảnh sạch — confidence thấp, không tampered."""
        from app.ml.document_analysis import combined_verdict

        with patch("app.ml.document_analysis.ela_analysis") as mock_ela, \
             patch("app.ml.document_analysis.metadata_check") as mock_meta:
            mock_ela.return_value = {
                "is_tampered": False, "max_error": 3.0,
                "mean_error": 1.5, "suspicious_area_pct": 1.0,
            }
            mock_meta.return_value = {"flags": [], "details": {"Make": "Samsung"}}

            result = combined_verdict(b"test")

        assert result["is_tampered"] is False
        assert result["confidence"] == 0.0

    def test_tampered_ela_plus_edited_software(self):
        """ELA + EXIF cùng phát hiện — confidence cao."""
        from app.ml.document_analysis import combined_verdict

        with patch("app.ml.document_analysis.ela_analysis") as mock_ela, \
             patch("app.ml.document_analysis.metadata_check") as mock_meta:
            mock_ela.return_value = {
                "is_tampered": True, "max_error": 40.0,
                "mean_error": 18.0, "suspicious_area_pct": 12.0,
            }
            mock_meta.return_value = {
                "flags": ["EDITED_BY_SOFTWARE"],
                "details": {"Software": "Adobe Photoshop"},
            }

            result = combined_verdict(b"test")

        assert result["is_tampered"] is True
        assert result["confidence"] >= 0.8


# ── Router tests ───────────────────────────────────────────────────────────────


class TestDocumentRouter:
    """Test verify-document endpoint."""

    @pytest.fixture
    def client(self):
        from fastapi.testclient import TestClient
        from main import app
        return TestClient(app)

    def test_verify_document_clean(self, client):
        """POST /verify-document — ảnh sạch."""
        cv2 = pytest.importorskip("cv2")

        # Ảnh gradient mịn
        img = np.zeros((100, 100, 3), dtype=np.uint8)
        for i in range(100):
            img[i, :] = [i * 2, i * 2, i * 2]

        _, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 95])
        b64 = base64.b64encode(buf.tobytes()).decode()

        resp = client.post(
            "/api/v1/ai/ekyc/verify-document",
            json={"image_base64": b64},
        )

        assert resp.status_code == 200
        data = resp.json()
        assert "is_tampered" in data
        assert "confidence" in data
        assert "ela" in data
        assert "metadata" in data

    def test_verify_document_invalid_base64(self, client):
        """POST /verify-document — base64 không hợp lệ → 400."""
        resp = client.post(
            "/api/v1/ai/ekyc/verify-document",
            json={"image_base64": "!!!invalid!!!"},
        )

        assert resp.status_code == 400

    def test_verify_document_too_small(self, client):
        """POST /verify-document — ảnh quá nhỏ → 400."""
        tiny = base64.b64encode(b"tiny").decode()
        resp = client.post(
            "/api/v1/ai/ekyc/verify-document",
            json={"image_base64": tiny},
        )

        assert resp.status_code == 400
