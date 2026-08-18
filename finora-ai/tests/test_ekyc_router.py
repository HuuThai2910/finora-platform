import pytest
import base64
import numpy as np
import cv2
from unittest.mock import patch, MagicMock
from fastapi.testclient import TestClient
from main import app


client = TestClient(app)


def _encode_dummy_image() -> str:
    """Tạo ảnh JPEG dummy mã hoá base64."""
    img = np.zeros((100, 100, 3), dtype=np.uint8)
    _, buf = cv2.imencode(".jpg", img)
    return base64.b64encode(buf.tobytes()).decode()


class TestOcrEndpoint:
    @patch("app.api.ekyc_router.get_ekyc_service")
    def test_ocr_success(self, mock_get_service):
        mock_service = MagicMock()
        mock_service.ocr.return_value = {
            "success": True,
            "id_number": "079204001234",
            "full_name": "NGUYEN VAN A",
            "date_of_birth": "01/01/2000",
            "gender": "Nam",
            "place_of_origin": "TP HCM",
            "confidence": 0.9,
        }
        mock_get_service.return_value = mock_service

        resp = client.post(
            "/api/v1/ai/ekyc/ocr",
            json={"image_base64": _encode_dummy_image()},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["success"] is True
        assert data["id_number"] == "079204001234"

    def test_ocr_empty_image_rejected(self):
        resp = client.post(
            "/api/v1/ai/ekyc/ocr",
            json={"image_base64": ""},
        )
        assert resp.status_code == 422


class TestFaceMatchEndpoint:
    @patch("app.api.ekyc_router.get_ekyc_service")
    def test_face_match_success(self, mock_get_service):
        mock_service = MagicMock()
        mock_service.face_match.return_value = {
            "match": True,
            "similarity": 0.85,
            "threshold": 0.6,
        }
        mock_get_service.return_value = mock_service

        img = _encode_dummy_image()
        resp = client.post(
            "/api/v1/ai/ekyc/face-match",
            json={"selfie_base64": img, "cccd_image_base64": img},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["match"] is True
        assert data["similarity"] == 0.85


class TestLivenessEndpoint:
    @patch("app.api.ekyc_router.get_ekyc_service")
    def test_liveness_success(self, mock_get_service):
        mock_service = MagicMock()
        mock_service.liveness.return_value = {
            "is_live": True,
            "confidence": 0.92,
            "method": "lbp_texture",
        }
        mock_get_service.return_value = mock_service

        resp = client.post(
            "/api/v1/ai/ekyc/liveness",
            json={"image_base64": _encode_dummy_image()},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["is_live"] is True
