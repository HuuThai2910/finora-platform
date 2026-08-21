"""Test active liveness — suy luận trên chuỗi landmark giả lập, không cần MediaPipe."""

import numpy as np
import pytest

from app.ml.ekyc.active_liveness import (
    BLINK,
    TURN_LEFT,
    TURN_RIGHT,
    ActionEvent,
    ActiveLivenessDetector,
    FrameMetrics,
    compute_confidence,
    detect_blinks,
    detect_turns,
    eye_aspect_ratio,
    match_sequence,
    select_best_frame,
)

OPEN_EAR = 0.30
CLOSED_EAR = 0.10
FRONTAL_YAW = 0.0
LEFT_YAW = -25.0
RIGHT_YAW = 25.0


def _metrics(ears: list[float], yaws: list[float], sharps: list[float] | None = None):
    """Dựng chuỗi FrameMetrics từ giá trị EAR/yaw của từng frame."""
    sharps = sharps or [1.0] * len(ears)
    return [
        FrameMetrics(index=i, ear=e, yaw=y, sharpness=s, bbox=(0, 0, 10, 10))
        for i, (e, y, s) in enumerate(zip(ears, yaws, sharps))
    ]


class TestEyeAspectRatio:
    def test_mat_mo_cho_ear_cao(self):
        # p1..p6: hai khoé mắt cách nhau 10, mí trên/dưới cách nhau 4
        points = np.array(
            [[0, 0], [3, 2], [7, 2], [10, 0], [7, -2], [3, -2]], dtype=float
        )
        assert eye_aspect_ratio(points) == pytest.approx(0.4)

    def test_mat_nham_cho_ear_thap(self):
        points = np.array(
            [[0, 0], [3, 0.2], [7, 0.2], [10, 0], [7, -0.2], [3, -0.2]], dtype=float
        )
        assert eye_aspect_ratio(points) < 0.1

    def test_khoe_mat_trung_nhau_khong_chia_cho_khong(self):
        points = np.zeros((6, 2))
        assert eye_aspect_ratio(points) == 0.0


class TestDetectBlinks:
    def test_mo_nham_mo_lai_tinh_la_nhay_mat(self):
        metrics = _metrics(
            ears=[OPEN_EAR, OPEN_EAR, CLOSED_EAR, OPEN_EAR],
            yaws=[FRONTAL_YAW] * 4,
        )
        events = detect_blinks(metrics)
        assert len(events) == 1
        assert events[0].action == BLINK
        assert events[0].complete_index == 3
        assert events[0].peak == CLOSED_EAR

    def test_nham_ma_khong_mo_lai_khong_tinh(self):
        metrics = _metrics(
            ears=[OPEN_EAR, CLOSED_EAR, CLOSED_EAR], yaws=[FRONTAL_YAW] * 3
        )
        assert detect_blinks(metrics) == []

    def test_nham_ngay_tu_frame_dau_khong_tinh(self):
        # Không quan sát được trạng thái mở trước đó — có thể là ảnh tĩnh nhắm mắt
        metrics = _metrics(ears=[CLOSED_EAR, OPEN_EAR], yaws=[FRONTAL_YAW] * 2)
        assert detect_blinks(metrics) == []

    def test_hai_lan_nhay_mat_cho_hai_su_kien(self):
        metrics = _metrics(
            ears=[OPEN_EAR, CLOSED_EAR, OPEN_EAR, CLOSED_EAR, OPEN_EAR],
            yaws=[FRONTAL_YAW] * 5,
        )
        assert [e.complete_index for e in detect_blinks(metrics)] == [2, 4]

    def test_frame_khong_thay_mat_bi_bo_qua(self):
        metrics = [
            FrameMetrics(index=0, ear=OPEN_EAR, yaw=FRONTAL_YAW),
            FrameMetrics(index=1),  # không thấy mặt
            FrameMetrics(index=2, ear=CLOSED_EAR, yaw=FRONTAL_YAW),
            FrameMetrics(index=3, ear=OPEN_EAR, yaw=FRONTAL_YAW),
        ]
        assert len(detect_blinks(metrics)) == 1


class TestDetectTurns:
    def test_quay_trai_roi_ve_chinh_dien(self):
        metrics = _metrics(
            ears=[OPEN_EAR] * 4,
            yaws=[FRONTAL_YAW, LEFT_YAW, -30.0, FRONTAL_YAW],
        )
        events = detect_turns(metrics)
        assert len(events) == 1
        assert events[0].action == TURN_LEFT
        assert events[0].complete_index == 3
        assert events[0].peak == -30.0

    def test_quay_phai_duoc_phan_biet_voi_quay_trai(self):
        metrics = _metrics(
            ears=[OPEN_EAR] * 3, yaws=[FRONTAL_YAW, RIGHT_YAW, FRONTAL_YAW]
        )
        assert detect_turns(metrics)[0].action == TURN_RIGHT

    def test_quay_ma_khong_ve_chinh_dien_khong_tinh(self):
        metrics = _metrics(ears=[OPEN_EAR] * 3, yaws=[FRONTAL_YAW, LEFT_YAW, LEFT_YAW])
        assert detect_turns(metrics) == []

    def test_nghieng_nhe_duoi_nguong_khong_tinh(self):
        metrics = _metrics(ears=[OPEN_EAR] * 3, yaws=[FRONTAL_YAW, -12.0, FRONTAL_YAW])
        assert detect_turns(metrics) == []


class TestMatchSequence:
    def test_dung_thu_tu_thi_dat(self):
        events = [
            ActionEvent(TURN_LEFT, 1, 3, -25.0),
            ActionEvent(BLINK, 5, 6, 0.1),
        ]
        results = match_sequence([TURN_LEFT, BLINK], events)
        assert [r["passed"] for r in results] == [True, True]

    def test_sai_thu_tu_thi_truot(self):
        # Nháy mắt xảy ra TRƯỚC khi quay đầu, nhưng challenge yêu cầu ngược lại
        events = [
            ActionEvent(BLINK, 1, 2, 0.1),
            ActionEvent(TURN_LEFT, 3, 4, -25.0),
        ]
        results = match_sequence([TURN_LEFT, BLINK], events)
        assert [r["passed"] for r in results] == [True, False]

    def test_thieu_hanh_dong_thi_truot(self):
        results = match_sequence([BLINK, TURN_RIGHT], [ActionEvent(BLINK, 1, 2, 0.1)])
        assert [r["passed"] for r in results] == [True, False]
        assert "quay đầu" in results[1]["evidence"]

    def test_hai_lan_cung_hanh_dong_dung_hai_su_kien_khac_nhau(self):
        events = [ActionEvent(BLINK, 1, 2, 0.1), ActionEvent(BLINK, 4, 5, 0.1)]
        results = match_sequence([BLINK, BLINK], events)
        assert [r["passed"] for r in results] == [True, True]

    def test_mot_su_kien_khong_dung_cho_hai_yeu_cau(self):
        results = match_sequence([BLINK, BLINK], [ActionEvent(BLINK, 1, 2, 0.1)])
        assert [r["passed"] for r in results] == [True, False]


class TestSelectBestFrame:
    def test_chon_frame_chinh_dien_net_nhat(self):
        metrics = _metrics(
            ears=[OPEN_EAR] * 3,
            yaws=[FRONTAL_YAW, FRONTAL_YAW, LEFT_YAW],
            sharps=[10.0, 50.0, 900.0],
        )
        assert select_best_frame(metrics).index == 1

    def test_khong_co_frame_chinh_dien_thi_lay_frame_net_nhat_con_thay_mat(self):
        metrics = _metrics(
            ears=[OPEN_EAR] * 2, yaws=[LEFT_YAW, RIGHT_YAW], sharps=[5.0, 20.0]
        )
        assert select_best_frame(metrics).index == 1

    def test_khong_thay_mat_thi_tra_none(self):
        assert select_best_frame([FrameMetrics(index=0)]) is None


class TestComputeConfidence:
    def test_chua_dat_thi_confidence_duoi_nua(self):
        results = [{"passed": True, "margin": 1.0}, {"passed": False, "margin": 0.0}]
        assert compute_confidence(results) == 0.25

    def test_dat_het_thi_confidence_tren_nua(self):
        results = [{"passed": True, "margin": 1.0}, {"passed": True, "margin": 0.4}]
        assert compute_confidence(results) == 0.7


class _StubDetector(ActiveLivenessDetector):
    """Detector dùng số đo dựng sẵn, bỏ qua tầng MediaPipe."""

    def __init__(self, metrics: list[FrameMetrics]):
        super().__init__()
        self._stub_metrics = metrics

    def measure(self, image, index: int) -> FrameMetrics:
        return self._stub_metrics[index]


class TestAnalyze:
    def test_dung_chuoi_hanh_dong_thi_is_live(self):
        metrics = _metrics(
            ears=[OPEN_EAR, OPEN_EAR, OPEN_EAR, OPEN_EAR, CLOSED_EAR, OPEN_EAR],
            yaws=[
                FRONTAL_YAW,
                LEFT_YAW,
                FRONTAL_YAW,
                FRONTAL_YAW,
                FRONTAL_YAW,
                FRONTAL_YAW,
            ],
        )
        result = _StubDetector(metrics).analyze(
            [None] * len(metrics), [TURN_LEFT, BLINK]
        )

        assert result["is_live"] is True
        assert [a["passed"] for a in result["actions"]] == [True, True]
        assert result["best_frame_index"] is not None
        assert result["confidence"] > 0.5

    def test_thieu_frame_thay_mat_thi_truot(self):
        metrics = [FrameMetrics(index=i) for i in range(4)]
        result = _StubDetector(metrics).analyze([None] * 4, [BLINK])

        assert result["is_live"] is False
        assert result["confidence"] == 0.0
        assert result["best_frame_index"] is None
        assert "frame thấy khuôn mặt" in result["actions"][0]["evidence"]

    def test_hanh_dong_khong_ho_tro_bi_tu_choi(self):
        with pytest.raises(ValueError):
            _StubDetector([]).analyze([], ["dance"])

    def test_danh_sach_hanh_dong_rong_bi_tu_choi(self):
        with pytest.raises(ValueError):
            _StubDetector([]).analyze([], [])
