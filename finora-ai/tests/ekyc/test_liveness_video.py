"""Giải mã video thật (``cv2.VideoWriter`` tổng hợp) qua toàn bộ pipeline liveness."""

import base64

import cv2
import numpy as np
import pytest

from app.ml.ekyc import face
from app.ml.ekyc import liveness_video as lv
from app.ml.ekyc.thresholds import LivenessThresholds

from .synthetic import (
    face_crop,
    moving_blob_frame,
    real_face_image_or_skip,
    write_video,
)

TH = LivenessThresholds()
STEPS = [
    lv.Step("FRONTAL", 1500),
    lv.Step("TURN_LEFT", 1300),
    lv.Step("FRONTAL", 1100),
    lv.Step("LOOK_UP", 1600),
    lv.Step("FRONTAL", 900),
    lv.Step("TURN_RIGHT", 1400),
    lv.Step("FRONTAL", 1000),
]
PORTRAIT = face.normalize(np.ones(128, np.float32))


def test_video_khong_co_mat_thi_retake_no_face(face_engine):
    video = write_video(moving_blob_frame, frames=135, fps=15.0)  # 9 giây
    result = lv.analyze_video(video, STEPS, PORTRAIT, face_engine, TH)
    assert result["decision"] == "RETAKE"
    assert "NO_FACE" in result["reasons"]
    assert result["video"]["codec"] == "mpeg4"
    assert result["video"]["fps"] == pytest.approx(15.0, abs=0.5)
    assert result["video"]["duration_ms"] == pytest.approx(9000, abs=100)
    assert result["video"]["pts_monotonic"] is True
    assert result["faces"]["sampled_frames"] >= 80  # ~10 khung/giây
    assert result["evidence_frames"] == []


def test_bytes_khong_phai_video():
    result = lv.analyze_video(b"day khong phai video", STEPS, PORTRAIT, None, TH)
    assert (result["decision"], result["reasons"]) == ("RETAKE", ["VIDEO_UNREADABLE"])


def test_video_qua_ngan(face_engine):
    video = write_video(moving_blob_frame, frames=15, fps=15.0)  # 1 giây
    result = lv.analyze_video(video, STEPS, PORTRAIT, face_engine, TH)
    assert (result["decision"], result["reasons"]) == ("RETAKE", ["VIDEO_TOO_SHORT"])


def test_dau_hieu_ffmpeg_trong_file():
    video = write_video(moving_blob_frame, frames=10, fps=10.0)
    assert lv.has_soft_encoder_tag(video)
    assert not lv.has_soft_encoder_tag(b"\x00\x00\x00\x18ftypmp42 du lieu may quay")


@pytest.mark.parametrize(
    ("fourcc", "name"),
    [("avc1", "h264"), ("hvc1", "hevc"), ("mp4v", "mpeg4"), ("vp09", "vp09")],
)
def test_ten_codec(fourcc, name):
    assert lv._codec_name(float(cv2.VideoWriter_fourcc(*fourcc))) == name


def test_anh_roi_khong_co_mat(face_engine):
    frames = [moving_blob_frame(i) for i in range(6)]
    result = lv.analyze_stills(
        frames, [0, 1000, 2000, 3000, 4000, 5000], STEPS, PORTRAIT, face_engine, TH
    )
    assert result["decision"] == "RETAKE"
    assert result["reasons"][0] == "NO_FACE"
    assert "STILLS_MODE" in result["reasons"]
    assert result["video"] is None


def _face_frame(face_image: np.ndarray, index: int, size=(640, 480)) -> np.ndarray:
    """Ảnh mặt đứng yên giữa khung (cỡ cầm máy một cánh tay), rung nhẹ 1–2 px."""
    width, height = size
    frame = np.full((height, width, 3), 200, np.uint8)
    portrait = cv2.resize(face_image, (360, 360))
    dx, dy = index % 3, (index // 3) % 2
    top, left = (height - 360) // 2, (width - 360) // 2
    frame[top + dy : top + 360 + dy, left + dx : left + 360 + dx] = portrait
    return frame


class TestRealFaceVideo:
    """Tấn công ảnh tĩnh: mặt thật nhưng không làm động tác → phải trượt."""

    def test_anh_tinh_khong_qua_duoc_thu_thach(self, face_engine):
        image = face_crop(face_engine, real_face_image_or_skip())
        video = write_video(lambda i: _face_frame(image, i), frames=270, fps=30.0)
        portrait_face = face_engine.detect(image, 0.6)[0]
        portrait = face_engine.embed(image, portrait_face)
        result = lv.analyze_video(video, STEPS, portrait, face_engine, TH)
        assert result["decision"] == "FAIL", result["reasons"]
        assert "ACTIONS_MISSING" in result["reasons"]
        assert result["face_match"]["zone"] == "HIGH"
        assert result["faces"]["single_face_ratio"] > 0.9
        assert result["identity"]["min_pair_cos"] > TH.t_same
        frame = result["evidence_frames"][0]
        assert frame["label"] == "FRONTAL"
        decoded = cv2.imdecode(
            np.frombuffer(base64.b64decode(frame["jpeg_b64"]), np.uint8),
            cv2.IMREAD_COLOR,
        )
        assert decoded.shape[1] == lv.EVIDENCE_WIDTH

    def test_video_xoay_90_do_khong_metadata_van_thay_mat(self, face_engine):
        image = face_crop(face_engine, real_face_image_or_skip())

        def rotated(i: int) -> np.ndarray:
            return cv2.rotate(_face_frame(image, i), cv2.ROTATE_90_CLOCKWISE)

        video = write_video(rotated, frames=90, fps=30.0, size=(480, 640))
        meta, observations, _ = lv.observe_video(video, STEPS, face_engine, TH)
        assert meta.readable
        with_face = sum(1 for o in observations if o.n_faces >= 1)
        assert with_face / len(observations) > 0.9


class UprightOnlyEngine:
    """YuNet giả: chỉ "thấy mặt" khi dải trắng đánh dấu nằm ở mép trên ảnh (ảnh đứng thẳng)."""

    def detect(self, image: np.ndarray, score_threshold: float) -> list[face.Face]:
        height, width = image.shape[:2]
        if image[: max(1, height // 12)].mean() < 200:
            return []
        return [
            face.Face(
                x=width * 0.3,
                y=height * 0.3,
                w=width * 0.4,
                h=height * 0.4,
                right_eye=(width * 0.42, height * 0.45),
                left_eye=(width * 0.58, height * 0.45),
                nose=(width * 0.5, height * 0.55),
                mouth_right=(width * 0.44, height * 0.62),
                mouth_left=(width * 0.56, height * 0.62),
                score=0.9,
            )
        ]

    def face_sharpness(self, image: np.ndarray, found: face.Face) -> float:
        return 100.0

    def embed(self, image: np.ndarray, found: face.Face) -> np.ndarray:
        return PORTRAIT


def _upright_frame(index: int, size=(480, 640)) -> np.ndarray:
    """Khung dọc 480×640 có dải trắng ở mép trên (đánh dấu chiều đứng)."""
    width, height = size
    frame = np.full((height, width, 3), 60 + index % 20, np.uint8)
    frame[: height // 10] = 255
    return frame


class TestRotationFallback:
    def test_anh_roi_bi_xoay_duoc_xoay_lai(self):
        frames = [
            cv2.rotate(_upright_frame(i), cv2.ROTATE_90_CLOCKWISE) for i in range(6)
        ]
        observations, _ = lv.observe_stills(
            frames, [i * 1000.0 for i in range(6)], UprightOnlyEngine(), TH
        )
        assert all(o.n_faces == 1 for o in observations)

    def test_anh_dung_thang_khong_bi_xoay(self):
        frames = [_upright_frame(i) for i in range(6)]
        store = lv.FrameStore()
        processor = lv._FrameProcessor(UprightOnlyEngine(), TH, store)
        processor.decide_rotation(frames)
        assert processor.rotation is None

    def test_video_thieu_metadata_xoay_duoc_thu_90_180_270_mot_lan(self, caplog):
        def rotated(i: int) -> np.ndarray:
            return cv2.rotate(_upright_frame(i), cv2.ROTATE_90_CLOCKWISE)

        video = write_video(rotated, frames=45, fps=15.0, size=(640, 480))
        with caplog.at_level("INFO", logger="app.ml.ekyc.liveness_video"):
            meta, observations, _ = lv.observe_video(
                video, STEPS, UprightOnlyEngine(), TH
            )
        assert meta.readable
        assert sum(o.n_faces for o in observations) == len(observations)
        assert "xoay 270°" in caplog.text

    def test_khong_goc_nao_thay_mat_thi_giu_nguyen(self):
        frames = [np.full((480, 640, 3), 40, np.uint8) for _ in range(5)]
        processor = lv._FrameProcessor(UprightOnlyEngine(), TH, lv.FrameStore())
        processor.decide_rotation(frames)
        assert processor.rotation is None

    def test_so_khung_tham_do_khoang_1_giay(self):
        assert lv.probe_frame_count(TH) == 10
