"""Test EkycService — cách hai lớp active và passive được ghép với nhau."""

import base64

import cv2
import numpy as np
import pytest

from app.services.ekyc.service import EkycService


def _frame_base64(fill: int = 128) -> str:
    image = np.full((60, 60, 3), fill, dtype=np.uint8)
    _, buf = cv2.imencode(".jpg", image)
    return base64.b64encode(buf.tobytes()).decode()


class _StubActive:
    """Thay tầng MediaPipe bằng kết quả dựng sẵn."""

    def __init__(self, is_live: bool, confidence: float, best_index: int | None = 0):
        self._result = {
            "is_live": is_live,
            "actions": [{"action": "blink", "passed": is_live, "evidence": "stub"}],
            "confidence": confidence,
            "method": "mediapipe_facemesh",
            "best_frame_index": best_index,
            "best_frame_bbox": (0, 0, 60, 60) if best_index is not None else None,
        }

    def analyze(self, images, expected_actions):
        return dict(self._result)


class _StubPassive:
    def __init__(self, is_live: bool, confidence: float):
        self._result = {
            "is_live": is_live,
            "confidence": confidence,
            "method": "lbp_texture",
        }

    def check_image(self, image, roi=None):
        return dict(self._result)


@pytest.fixture
def service():
    return EkycService()


def test_hai_lop_cung_dat_thi_ket_luan_la_nguoi_that(service):
    service._active_liveness = _StubActive(True, 0.9)
    service._liveness = _StubPassive(True, 0.7)

    result = service.active_liveness([_frame_base64()], ["blink"])

    assert result["is_live"] is True
    # Confidence lấy theo lớp yếu hơn — không được lạc quan hơn bằng chứng yếu nhất
    assert result["confidence"] == 0.7
    assert result["passive_check"]["is_live"] is True


def test_dung_hanh_dong_nhung_truot_texture_thi_khong_dat(service):
    """Video quay sẵn phát trên màn hình có thể có nháy mắt — lớp texture chặn lại."""
    service._active_liveness = _StubActive(True, 0.9)
    service._liveness = _StubPassive(False, 0.1)

    result = service.active_liveness([_frame_base64()], ["blink"])

    assert result["is_live"] is False
    assert result["passive_check"]["is_live"] is False


def test_truot_hanh_dong_thi_khong_dat_du_texture_tot(service):
    service._active_liveness = _StubActive(False, 0.25)
    service._liveness = _StubPassive(True, 0.9)

    result = service.active_liveness([_frame_base64()], ["blink"])

    assert result["is_live"] is False


def test_khong_co_frame_tot_thi_bo_qua_lop_texture(service):
    service._active_liveness = _StubActive(False, 0.0, best_index=None)
    service._liveness = _StubPassive(True, 0.9)

    result = service.active_liveness([_frame_base64()], ["blink"])

    assert result["is_live"] is False
    assert result["passive_check"] is None


def test_frame_hong_bi_loai_bo(service):
    service._active_liveness = _StubActive(True, 0.9)
    service._liveness = _StubPassive(True, 0.8)

    result = service.active_liveness(["khong-phai-anh", _frame_base64()], ["blink"])

    assert result["is_live"] is True


def test_khong_giai_ma_duoc_frame_nao(service):
    result = service.active_liveness(["khong-phai-anh"], ["blink"])

    assert result["is_live"] is False
    assert result["confidence"] == 0.0
    assert result["method"] == "none"
    assert result["actions"][0]["passed"] is False
