import base64
from unittest.mock import MagicMock, patch

import cv2
import numpy as np
from fastapi.testclient import TestClient

from app.ml.ekyc.thresholds import MAX_FRAMES
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


class TestActiveLivenessEndpoint:
    @patch("app.api.ekyc_router.get_ekyc_service")
    def test_active_liveness_success(self, mock_get_service):
        mock_service = MagicMock()
        mock_service.active_liveness.return_value = {
            "is_live": True,
            "actions": [
                {"action": "turn_left", "passed": True, "evidence": "yaw -25 độ"},
                {"action": "blink", "passed": True, "evidence": "EAR thấp nhất 0.1"},
            ],
            "confidence": 0.82,
            "method": "mediapipe_facemesh",
            "best_frame_index": 4,
            "passive_check": {"is_live": True, "confidence": 0.9, "method": "lbp_texture"},
        }
        mock_get_service.return_value = mock_service

        frames = [_encode_dummy_image() for _ in range(5)]
        resp = client.post(
            "/api/v1/ai/ekyc/liveness-active",
            json={"frames": frames, "expected_actions": ["turn_left", "blink"]},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["is_live"] is True
        assert data["best_frame_index"] == 4
        assert [a["action"] for a in data["actions"]] == ["turn_left", "blink"]

    @patch("app.api.ekyc_router.get_ekyc_service")
    def test_hanh_dong_khong_ho_tro_tra_400(self, mock_get_service):
        mock_service = MagicMock()
        mock_service.active_liveness.side_effect = ValueError("Hành động không hỗ trợ")
        mock_get_service.return_value = mock_service

        resp = client.post(
            "/api/v1/ai/ekyc/liveness-active",
            json={"frames": [_encode_dummy_image()], "expected_actions": ["dance"]},
        )
        assert resp.status_code == 400

    def test_khong_co_frame_bi_tu_choi(self):
        resp = client.post(
            "/api/v1/ai/ekyc/liveness-active",
            json={"frames": [], "expected_actions": ["blink"]},
        )
        assert resp.status_code == 422

    def test_qua_nhieu_frame_bi_tu_choi(self):
        """Giới hạn số frame chặn request nhồi ảnh làm nghẽn service."""
        frames = [_encode_dummy_image() for _ in range(MAX_FRAMES + 1)]
        resp = client.post(
            "/api/v1/ai/ekyc/liveness-active",
            json={"frames": frames, "expected_actions": ["blink"]},
        )
        assert resp.status_code == 422
