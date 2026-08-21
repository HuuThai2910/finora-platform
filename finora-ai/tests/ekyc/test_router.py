"""Kiểm tra endpoint OCR eKYC ở tầng router."""

import base64
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def _encode_dummy_image() -> str:
    return base64.b64encode(b"anh-gia-lap").decode()


class TestOcrEndpoint:
    @patch("app.api.ekyc_router.get_ekyc_service")
    def test_ocr_success(self, mock_get_service):
        mock_service = MagicMock()
        mock_service.ocr.return_value = {
            "success": True,
            "id_number": "079204001234",
            "full_name": "NGUYỄN VĂN A",
            "date_of_birth": "01/01/2000",
            "gender": "Nam",
            "place_of_origin": "TP Hồ Chí Minh",
            "address": "1 Lê Lợi, Quận 1, TP Hồ Chí Minh",
            "confidence": 0.9,
        }
        mock_get_service.return_value = mock_service

        response = client.post(
            "/api/v1/ai/ekyc/ocr", json={"image_base64": _encode_dummy_image()}
        )

        assert response.status_code == 200
        body = response.json()
        assert body["success"] is True
        assert body["id_number"] == "079204001234"
        assert body["address"].startswith("1 Lê Lợi")

    def test_ocr_empty_image_rejected(self):
        response = client.post("/api/v1/ai/ekyc/ocr", json={"image_base64": ""})
        assert response.status_code == 422
