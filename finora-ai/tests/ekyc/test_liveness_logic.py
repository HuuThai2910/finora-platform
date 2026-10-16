"""Máy trạng thái + khớp chặt liveness trên chuỗi điểm mốc tổng hợp (không cần model)."""

import math
from dataclasses import replace

import numpy as np
import pytest

from app.ml.ekyc import liveness_video as lv
from app.ml.ekyc.face import Face
from app.ml.ekyc.thresholds import LivenessThresholds

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
EXPECTED = ["TURN_LEFT", "LOOK_UP", "TURN_RIGHT"]
P0 = 0.55  # tỉ số ngẩng/cúi lúc nhìn thẳng
OWNER = np.ones(128, dtype=np.float32)


def face_at(
    r: float, p: float, ied: float = 80.0, cx: float = 320.0, cy: float = 220.0
) -> Face:
    """Khuôn mặt giả có đúng tỉ số quay ``r`` và ngẩng/cúi ``p``."""
    mouth = 60.0
    return Face(
        x=cx - 90,
        y=cy - 90,
        w=180,
        h=200,
        right_eye=(cx - ied / 2, cy),
        left_eye=(cx + ied / 2, cy),
        nose=(cx + r * ied, cy + p * mouth),
        mouth_right=(cx - 25, cy + mouth),
        mouth_left=(cx + 25, cy + mouth),
        score=0.95,
    )


def pose_at(t: float, plan: list[tuple[str, float, float]]) -> tuple[float, float]:
    """``plan`` = [(hành động, bắt đầu ms, kéo dài ms)]; biên độ hình sin, đỉnh ở giữa."""
    r, p = 0.0, P0
    for action, start, length in plan:
        if start <= t <= start + length:
            bump = math.sin(math.pi * (t - start) / length)
            if action == "TURN_LEFT":
                r += 0.5 * bump
            elif action == "TURN_RIGHT":
                r -= 0.5 * bump
            elif action == "LOOK_UP":
                p -= 0.3 * bump
            elif action == "LOOK_DOWN":
                p += 0.3 * bump
    return r, p


def schedule(steps=STEPS) -> list[tuple[str, float, float]]:
    """Người dùng làm đúng lịch: mỗi hành động chiếm trọn bước của nó."""
    plan, elapsed = [], 0.0
    for step in steps:
        if step.action != "FRONTAL":
            plan.append((step.action, elapsed, float(step.hold_ms)))
        elapsed += step.hold_ms
    return plan


def track(plan, total_ms: float = 8800.0, step_ms: float = 100.0, overrides=None):
    """Chuỗi quan sát 10 khung/giây theo ``plan``; ``overrides`` sửa từng khung theo chỉ số."""
    overrides = overrides or {}
    observations = []
    for index in range(int(total_ms / step_ms) + 1):
        t = index * step_ms
        r, p = pose_at(t, plan)
        obs = lv.Observation(t, 1, face_at(r, p), 160.0, 0.55, 120.0, index)
        if index in overrides:
            obs = replace(obs, **overrides[index])
        observations.append(obs)
    return observations


def run(
    observations,
    *,
    steps=STEPS,
    mode=lv.MODE_VIDEO,
    meta=None,
    embed=None,
    portrait=OWNER,
    th=TH,
):
    meta = (
        meta
        if meta is not None
        else lv.VideoMeta(True, 8900.0, 30.0, True, 40.0, "h264", 267, False)
    )
    return lv.analyze_observations(
        mode=mode,
        meta=meta if mode == lv.MODE_VIDEO else None,
        observations=observations,
        steps=steps,
        portrait_embedding=portrait,
        embed=embed or (lambda obs: OWNER),
        render_evidence=lambda obs: b"jpeg",
        th=th,
    )


class TestPoseAndEvents:
    def test_ti_so_tu_diem_moc(self):
        r, p = lv.pose_ratios(face_at(0.3, 0.5))
        assert r == pytest.approx(0.3)
        assert p == pytest.approx(0.5)

    def test_dung_lich_ra_dung_chuoi_va_dinh_o_giua_buoc(self):
        samples = lv.pose_samples(track(schedule()))
        events = lv.detect_events(samples, (0.0, P0), TH)
        assert [e.action for e in events] == EXPECTED
        for event, expected_t in zip(events, lv.expected_peak_times(STEPS)):
            assert abs(event.t_ms - expected_t) <= 100

    def test_lech_duoi_nguong_khong_tinh(self):
        samples = [
            lv.PoseSample(t * 100.0, 0.2 * math.sin(t / 3), P0, t) for t in range(80)
        ]
        assert lv.detect_events(samples, (0.0, P0), TH) == []

    def test_khong_ve_chinh_dien_thi_khong_tinh(self):
        samples = [
            lv.PoseSample(0, 0.0, P0, 0),
            lv.PoseSample(100, 0.4, P0, 1),
            lv.PoseSample(200, 0.3, P0, 2),
            lv.PoseSample(300, 0.2, P0, 3),
        ]
        # 0,2/0,25 = 0,8 > 0,5: chưa "về dưới θ/2".
        assert lv.detect_events(samples, (0.0, P0), TH) == []

    def test_chua_tung_chinh_dien_thi_khong_tinh(self):
        samples = [lv.PoseSample(t * 100.0, 0.5, P0, t) for t in range(5)]
        samples.append(lv.PoseSample(600.0, 0.0, P0, 5))
        assert lv.detect_events(samples, (0.0, P0), TH) == []

    def test_quay_kem_hoi_ngang_chi_la_mot_su_kien(self):
        samples = [
            lv.PoseSample(0, 0.0, P0, 0),
            lv.PoseSample(100, 0.5, P0 - 0.17, 1),
            lv.PoseSample(200, 0.0, P0, 2),
        ]
        events = lv.detect_events(samples, (0.0, P0), TH)
        assert [e.action for e in events] == ["TURN_LEFT"]

    def test_yaw_invert_dao_trai_phai(self):
        samples = lv.pose_samples(track(schedule()))
        events = lv.detect_events(samples, (0.0, P0), replace(TH, yaw_invert=True))
        assert [e.action for e in events] == ["TURN_RIGHT", "LOOK_UP", "TURN_LEFT"]


class TestStrictMatching:
    @pytest.mark.parametrize(
        ("detected", "code", "extra"),
        [
            (EXPECTED, "ACTIONS_MATCH", 0),
            (["TURN_LEFT", "LOOK_UP"], "ACTIONS_MISSING", 0),
            (["TURN_LEFT", "TURN_RIGHT", "LOOK_UP", "TURN_RIGHT"], "ACTIONS_EXTRA", 1),
            # Clip quay sẵn đủ mọi hướng: chứa chuỗi kỳ vọng nhưng thừa → trượt.
            (["TURN_LEFT", "LOOK_UP", "TURN_RIGHT", "LOOK_DOWN"], "ACTIONS_EXTRA", 1),
            (["LOOK_UP", "TURN_LEFT", "TURN_RIGHT"], "ACTIONS_MISMATCH", 1),
            ([], "ACTIONS_MISSING", 0),
        ],
    )
    def test_so_khop_chat(self, detected, code, extra):
        assert lv.compare_sequences(EXPECTED, detected) == (code, extra)

    def test_khoang_cach_dinh(self):
        expected = lv.expected_peak_times(STEPS)
        assert lv.intervals_ok(expected, expected, TH)
        shifted = [expected[0], expected[1] + 1500, expected[2] + 1500]
        assert not lv.intervals_ok(expected, shifted, TH)
        small = [expected[0], expected[1] + 500, expected[2] + 500]
        assert lv.intervals_ok(expected, small, TH)  # ≤ max(0,7 s; 35%)


class TestDecision:
    def test_dung_dong_tac_va_khop_mat_thi_pass(self):
        result = run(track(schedule()))
        assert result["decision"] == "PASS", result["reasons"]
        assert result["reasons"] == ["ACTIONS_MATCH", "FACE_MATCH_HIGH"]
        assert result["actions"]["detected"] == EXPECTED
        assert result["actions"]["intervals_ok"] is True
        assert result["face_match"]["zone"] == "HIGH"
        assert result["identity"]["frames_checked"] >= 4
        labels = [frame["label"] for frame in result["evidence_frames"]]
        assert labels == ["FRONTAL", *EXPECTED]
        assert result["faces"]["min_ied_px"] == 160.0
        assert result["thresholds_version"] == TH.version

    def test_thua_dong_tac_thi_fail(self):
        plan = [*schedule(), ("LOOK_DOWN", 7900.0, 800.0)]
        result = run(track(plan))
        assert result["decision"] == "FAIL"
        assert "ACTIONS_EXTRA" in result["reasons"]
        assert result["actions"]["extra_events"] == 1

    def test_thieu_dong_tac_thi_fail(self):
        plan = [a for a in schedule() if a[0] != "LOOK_UP"]
        result = run(track(plan))
        assert result["decision"] == "FAIL"
        assert "ACTIONS_MISSING" in result["reasons"]

    def test_sai_thu_tu_thi_fail(self):
        plan = schedule()
        swapped = [
            (plan[1][0], plan[0][1], plan[0][2]),
            (plan[0][0], plan[1][1], plan[1][2]),
            plan[2],
        ]
        result = run(track(swapped))
        assert result["decision"] == "FAIL"
        assert "ACTIONS_MISMATCH" in result["reasons"]

    def test_lech_nhip_thi_fail(self):
        # Quay trái trễ ~1 s (đỉnh 3150 ms thay vì 2150 ms) nhưng vẫn đúng thứ tự.
        plan = schedule()
        late = [("TURN_LEFT", 2600.0, 1100.0), plan[1], plan[2]]
        result = run(track(late))
        assert result["actions"]["detected"] == EXPECTED
        assert "INTERVALS_MISMATCH" in result["reasons"]
        assert result["decision"] == "FAIL"

    def test_vung_xam_thi_review(self):
        portrait = np.zeros(128, dtype=np.float32)
        portrait[:2] = (1.0, 0.0)
        frame = np.zeros(128, dtype=np.float32)
        frame[:2] = (0.35, math.sqrt(1 - 0.35**2))  # cosine 0,35 ∈ [T_low, T_high)
        result = run(track(schedule()), embed=lambda obs: frame, portrait=portrait)
        assert result["decision"] == "REVIEW"
        assert "FACE_MATCH_GRAY" in result["reasons"]
        assert result["face_match"]["zone"] == "GRAY"

    def test_khong_khop_mat_thi_fail(self):
        stranger = np.zeros(128, dtype=np.float32)
        stranger[1] = 1.0
        portrait = np.zeros(128, dtype=np.float32)
        portrait[0] = 1.0
        result = run(track(schedule()), embed=lambda obs: stranger, portrait=portrait)
        assert result["decision"] == "FAIL"
        assert "FACE_MISMATCH" in result["reasons"]
        assert result["face_match"]["zone"] == "LOW"

    def test_chen_mot_khung_nguoi_khac_thi_identity_fail(self):
        intruder = np.zeros(128, dtype=np.float32)
        intruder[5] = 1.0
        # Khung 40 (đoạn FRONTAL giữa) nét nhất → chắc chắn được chọn để so mặt.
        observations = track(schedule(), overrides={40: {"sharpness": 999.0}})
        result = run(
            observations,
            embed=lambda o: intruder if o.frame_index == 40 else OWNER,
            portrait=intruder,
        )
        assert result["decision"] == "FAIL"
        assert "IDENTITY_INCONSISTENT" in result["reasons"]
        assert result["identity"]["min_pair_cos"] < TH.t_same

    def test_nhieu_khuon_mat_thi_fail(self):
        overrides = {i: {"n_faces": 2} for i in range(10, 30)}
        result = run(track(schedule(), overrides=overrides))
        assert result["decision"] == "FAIL"
        assert "MULTIPLE_FACES" in result["reasons"]
        assert result["faces"]["multi_face_frames"] == 20

    def test_khong_thay_mat_thi_retake(self):
        overrides = {
            i: {
                "n_faces": 0,
                "face": None,
                "ied_px": None,
                "sharpness": None,
                "frame_index": None,
            }
            for i in range(10, 89)
        }
        result = run(track(schedule(), overrides=overrides))
        assert result["decision"] == "RETAKE"
        assert result["reasons"] == ["NO_FACE"]

    def test_qua_toi_thi_retake(self):
        overrides = {i: {"brightness": 0.1} for i in range(89)}
        result = run(track(schedule(), overrides=overrides))
        assert result["decision"] == "RETAKE"
        assert "TOO_DARK" in result["reasons"]

    def test_mat_qua_nho_thi_retake(self):
        overrides = {i: {"ied_px": 25.0} for i in range(89)}
        result = run(track(schedule(), overrides=overrides))
        assert result["decision"] == "RETAKE"
        assert "FACE_TOO_SMALL" in result["reasons"]

    def test_video_mo_thi_retake(self):
        overrides = {i: {"sharpness": 5.0} for i in range(89)}
        result = run(track(schedule(), overrides=overrides))
        assert result["decision"] == "RETAKE"
        assert "BLURRY" in result["reasons"]

    def test_mat_bam_khong_lien_tuc_thi_review(self):
        # Mất mặt 1,5 s ở giữa video (vẫn đủ 80% khung có mặt).
        overrides = {
            i: {
                "n_faces": 0,
                "face": None,
                "ied_px": None,
                "sharpness": None,
                "frame_index": None,
            }
            for i in range(26, 41)
        }
        observations = track(schedule(), overrides=overrides)
        result = run(observations)
        assert "TRACK_BROKEN" in result["reasons"]
        assert result["faces"]["track_continuous"] is False


class TestVideoChecks:
    def _meta(self, **changes):
        base = lv.VideoMeta(True, 8900.0, 30.0, True, 40.0, "h264", 267, False)
        return replace(base, **changes)

    def test_khong_doc_duoc_video(self):
        result = run([], meta=self._meta(readable=False, frame_count=0))
        assert (result["decision"], result["reasons"]) == (
            "RETAKE",
            ["VIDEO_UNREADABLE"],
        )

    def test_video_qua_ngan(self):
        result = run(track(schedule()), meta=self._meta(duration_ms=5000.0))
        assert (result["decision"], result["reasons"]) == (
            "RETAKE",
            ["VIDEO_TOO_SHORT"],
        )

    def test_video_qua_dai_khong_khop_nhip(self):
        result = run(track(schedule()), meta=self._meta(duration_ms=20000.0))
        assert result["decision"] == "FAIL"
        assert "INTERVALS_MISMATCH" in result["reasons"]

    @pytest.mark.parametrize(
        "changes",
        [{"max_gap_ms": 400.0}, {"pts_monotonic": False}, {"fps": 8.0}],
    )
    def test_video_co_khoang_ho_thi_review(self, changes):
        result = run(track(schedule()), meta=self._meta(**changes))
        assert result["decision"] == "REVIEW"
        assert "VIDEO_GAPS" in result["reasons"]

    def test_dau_hieu_bo_ma_hoa_phan_mem_thi_review(self):
        result = run(track(schedule()), meta=self._meta(encoder_tag=True))
        assert result["decision"] == "REVIEW"
        assert "SOFT_ENCODER_TAG" in result["reasons"]

    def test_video_block(self):
        result = run(track(schedule()))
        assert result["video"] == {
            "duration_ms": 8900,
            "fps": 30.0,
            "pts_monotonic": True,
            "max_gap_ms": 40,
            "codec": "h264",
        }


STILLS_STEPS = (
    lv.Step("FRONTAL", 2000),
    lv.Step("TURN_LEFT", 2000),
    lv.Step("FRONTAL", 2000),
    lv.Step("LOOK_DOWN", 2000),
    lv.Step("FRONTAL", 2000),
)


class TestStills:
    STEPS = STILLS_STEPS

    def test_anh_roi_dung_dong_tac_toi_da_review(self):
        plan = schedule(self.STEPS)
        observations = track(plan, total_ms=10000.0, step_ms=1000.0)
        result = run(observations, steps=self.STEPS, mode=lv.MODE_STILLS)
        assert result["decision"] == "REVIEW"
        assert "STILLS_MODE" in result["reasons"]
        assert "ACTIONS_MATCH" in result["reasons"]
        assert result["video"] is None

    def test_anh_roi_sai_dong_tac_van_fail(self):
        plan = [("TURN_RIGHT", 2000.0, 2000.0), ("LOOK_DOWN", 6000.0, 2000.0)]
        observations = track(plan, total_ms=10000.0, step_ms=1000.0)
        result = run(observations, steps=self.STEPS, mode=lv.MODE_STILLS)
        assert result["decision"] == "FAIL"


def test_chon_khung_cung_nguoi_rai_deu_va_gom_khung_net_nhat():
    observations = [
        lv.Observation(t * 100.0, 1, face_at(0, P0), 160.0, 0.5, float(t % 7), t)
        for t in range(60)
    ]
    observations[33] = replace(observations[33], sharpness=500.0)
    chosen = lv.select_identity_frames(observations, 6)
    assert len(chosen) == 6
    assert observations[33] in chosen
    times = [o.t_ms for o in chosen]
    assert times == sorted(times)
    assert times[-1] - times[0] >= 4000  # rải theo thời gian


def test_max_duration_theo_cach_app_dung_quay():
    total = lv.total_hold_ms(STEPS)  # 8800 ms → app dừng ở ceil(8,8)+3 = 12 s
    assert lv.max_duration_ms(STEPS, TH) == 9000 + 3000 + 500
    assert total == 8800
