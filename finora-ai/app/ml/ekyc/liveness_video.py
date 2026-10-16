"""Liveness theo thử thách + so mặt với chân dung CCCD, phân tích video ở server.

Luồng (đề xuất mục 3.4, hợp đồng EKYC-002 mục 2.2):

1. Đọc video bằng ``cv2.VideoCapture`` (file tạm), kiểm PTS tăng đơn điệu, khoảng hở,
   fps, thời lượng so với tổng ``hold_ms`` của thử thách.
2. Lấy mẫu ~10 khung/giây, YuNet trên khung rộng 480 px; IED đổi về toạ độ gốc.
3. Tư thế đầu từ 5 điểm mốc:
   ``r = (x_mũi − x_giữa_hai_mắt) / |x_mắt_phải − x_mắt_trái|`` (quay trái/phải),
   ``p = (y_mũi − y_mắt) / (y_miệng − y_mắt)`` (ngẩng/cúi). Mức nền lấy từ đoạn
   FRONTAL đầu. Video camera trước **không lật gương**: người quay sang trái của họ thì
   mũi dịch sang phải ảnh, ``r`` tăng → ``TURN_LEFT`` (``yaw_invert`` đảo dấu).
4. Sự kiện = chính diện → lệch ≥ θ → về dưới θ/2 (trễ hai ngưỡng chống rung); thời
   điểm sự kiện = đỉnh. Một lần lệch chỉ sinh **một** sự kiện, theo trục lệch mạnh nhất
   (đã chuẩn hoá theo θ) — quay đầu kèm hơi ngẩng không thành hai hành động.
5. Khớp **chặt**: chuỗi phát hiện phải bằng đúng chuỗi kỳ vọng (thừa/thiếu/sai thứ tự
   đều trượt) — vá lỗ của bản cũ, nơi một clip làm đủ mọi hướng qua được mọi thử thách.
   Khoảng cách giữa các đỉnh phải khớp lịch ``hold_ms`` ngẫu nhiên.
6. Cùng một người: embedding SFace ở 4–6 khung chính diện rải theo thời gian, cosine
   nhỏ nhất ≥ ``t_same`` — chặn kiểu chèn một khung ảnh nạn nhân rất nét vào video.
7. So mặt: khung chính diện nét nhất (đã qua bước 6) với embedding chân dung thẻ.

Phần quyết định (``analyze_observations``) là hàm thuần trên danh sách quan sát, nên
test được bằng chuỗi điểm mốc tổng hợp mà không cần video hay model.
"""

from __future__ import annotations

import base64
import itertools
import logging
import math
import os
import statistics
import tempfile
from collections.abc import Callable, Sequence
from dataclasses import dataclass

import cv2
from numpy.typing import NDArray

from app.ml.ekyc import image_io
from app.ml.ekyc.face import Face, FaceEngine, cosine
from app.ml.ekyc.thresholds import LivenessThresholds

logger = logging.getLogger(__name__)

FRONTAL = "FRONTAL"
TURN_LEFT = "TURN_LEFT"
TURN_RIGHT = "TURN_RIGHT"
LOOK_UP = "LOOK_UP"
LOOK_DOWN = "LOOK_DOWN"
ACTIONS = (TURN_LEFT, TURN_RIGHT, LOOK_UP, LOOK_DOWN)
ALL_STEP_ACTIONS = (FRONTAL, *ACTIONS)

MODE_VIDEO = "VIDEO"
MODE_STILLS = "STILLS"

DECISION_PASS = "PASS"
DECISION_REVIEW = "REVIEW"
DECISION_FAIL = "FAIL"
DECISION_RETAKE = "RETAKE"

VIDEO_UNREADABLE = "VIDEO_UNREADABLE"
VIDEO_TOO_SHORT = "VIDEO_TOO_SHORT"
VIDEO_GAPS = "VIDEO_GAPS"
NO_FACE = "NO_FACE"
FACE_TOO_SMALL = "FACE_TOO_SMALL"
TOO_DARK = "TOO_DARK"
BLURRY = "BLURRY"
MULTIPLE_FACES = "MULTIPLE_FACES"
TRACK_BROKEN = "TRACK_BROKEN"
ACTIONS_MISMATCH = "ACTIONS_MISMATCH"
ACTIONS_MISSING = "ACTIONS_MISSING"
ACTIONS_EXTRA = "ACTIONS_EXTRA"
INTERVALS_MISMATCH = "INTERVALS_MISMATCH"
IDENTITY_INCONSISTENT = "IDENTITY_INCONSISTENT"
FACE_MISMATCH = "FACE_MISMATCH"
FACE_MATCH_GRAY = "FACE_MATCH_GRAY"
STILLS_MODE = "STILLS_MODE"
SOFT_ENCODER_TAG = "SOFT_ENCODER_TAG"
ACTIONS_MATCH = "ACTIONS_MATCH"
FACE_MATCH_HIGH = "FACE_MATCH_HIGH"

RETAKE_REASONS = frozenset(
    {VIDEO_UNREADABLE, VIDEO_TOO_SHORT, NO_FACE, FACE_TOO_SMALL, TOO_DARK, BLURRY}
)
FAIL_REASONS = frozenset(
    {
        MULTIPLE_FACES,
        ACTIONS_MISMATCH,
        ACTIONS_MISSING,
        ACTIONS_EXTRA,
        INTERVALS_MISMATCH,
        IDENTITY_INCONSISTENT,
        FACE_MISMATCH,
    }
)
REVIEW_REASONS = frozenset(
    {FACE_MATCH_GRAY, STILLS_MODE, VIDEO_GAPS, TRACK_BROKEN, SOFT_ENCODER_TAG}
)

ZONE_HIGH = "HIGH"
ZONE_GRAY = "GRAY"
ZONE_LOW = "LOW"

#: Khung lưu tạm để trích embedding/khung bằng chứng: cạnh dài tối đa (854 giữ được
#: chiều rộng 480 cho cả video dọc 9:16 lẫn 4:3).
WORK_MAX_SIDE = 854
EVIDENCE_WIDTH = 480
EVIDENCE_QUALITY = 80
MAX_EVIDENCE_FRAMES = 4
#: Dùng các khung lấy mẫu trong ~1 giây đầu để đoán video có bị xoay mà metadata
#: không được áp (Android ghi góc xoay vào metadata MP4 thay vì xoay điểm ảnh).
ROTATION_PROBE_SECONDS = 1.0
#: "Gần như không thấy mặt": tỉ lệ khung thấy mặt ≤ mức này thì mới thử xoay.
ROTATION_TRIGGER_RATIO = 0.2
STILLS_PROBE_FRAMES = 4
_ROTATION_DEGREES = {
    cv2.ROTATE_90_CLOCKWISE: 90,
    cv2.ROTATE_180: 180,
    cv2.ROTATE_90_COUNTERCLOCKWISE: 270,
}
#: Chặn video dị dạng giải mã mãi không hết.
MAX_DECODED_FRAMES = 3600
#: Chuỗi do bộ mã hoá/biên tập phần mềm ghi vào file (máy quay điện thoại không ghi).
SOFT_ENCODER_MARKERS = (b"Lavf", b"x264 - core", b"x265 (build", b"HandBrake")
#: Mặt phụ nhỏ hơn tỉ lệ này so với mặt lớn nhất coi là nhiễu nền, không tính "nhiều người".
SECONDARY_FACE_MIN_RATIO = 0.25


@dataclass(frozen=True)
class Step:
    """Một bước thử thách: hành động + thời gian giữ (ms)."""

    action: str
    hold_ms: int


@dataclass(frozen=True)
class Observation:
    """Số đo một khung đã lấy mẫu. Toạ độ ``face`` theo khung lưu tạm."""

    t_ms: float
    n_faces: int
    face: Face | None
    ied_px: float | None
    brightness: float
    sharpness: float | None
    frame_index: int | None


@dataclass(frozen=True)
class VideoMeta:
    """Thông số video đo được khi giải mã."""

    readable: bool
    duration_ms: float
    fps: float
    pts_monotonic: bool
    max_gap_ms: float
    codec: str | None
    frame_count: int
    encoder_tag: bool


@dataclass(frozen=True)
class PoseSample:
    t_ms: float
    r: float
    p: float
    index: int


@dataclass(frozen=True)
class Event:
    """Một hành động phát hiện được; ``t_ms`` là thời điểm đỉnh."""

    action: str
    t_ms: float
    index: int
    magnitude: float


# ── Hình học tư thế và lịch thử thách ─────────────────────────────────


def pose_ratios(face: Face) -> tuple[float, float] | None:
    """``(r, p)`` từ 5 điểm mốc; ``None`` khi điểm mốc suy biến."""
    eye_dx = abs(face.right_eye[0] - face.left_eye[0])
    mid_x = (face.right_eye[0] + face.left_eye[0]) / 2.0
    eye_y = (face.right_eye[1] + face.left_eye[1]) / 2.0
    mouth_y = (face.mouth_right[1] + face.mouth_left[1]) / 2.0
    if eye_dx < 1e-3 or mouth_y - eye_y < 1e-3:
        return None
    return (face.nose[0] - mid_x) / eye_dx, (face.nose[1] - eye_y) / (mouth_y - eye_y)


def total_hold_ms(steps: Sequence[Step]) -> float:
    return float(sum(step.hold_ms for step in steps))


def expected_actions(steps: Sequence[Step]) -> list[str]:
    return [step.action for step in steps if step.action != FRONTAL]


def expected_peak_times(steps: Sequence[Step]) -> list[float]:
    """Thời điểm kỳ vọng của mỗi hành động = tâm khoảng giữ của bước đó."""
    times: list[float] = []
    elapsed = 0.0
    for step in steps:
        if step.action != FRONTAL:
            times.append(elapsed + step.hold_ms / 2.0)
        elapsed += step.hold_ms
    return times


def first_frontal_window(steps: Sequence[Step]) -> tuple[float, float] | None:
    elapsed = 0.0
    for step in steps:
        if step.action == FRONTAL:
            return elapsed, elapsed + step.hold_ms
        elapsed += step.hold_ms
    return None


def max_duration_ms(steps: Sequence[Step], th: LivenessThresholds) -> float:
    """Trần thời lượng: app dừng quay ở ``ceil(tổng hold/1000) + 3`` giây; cộng 0,5 s
    cho độ trễ dừng ghi để video hợp lệ không bị coi là quá dài."""
    return (
        math.ceil(total_hold_ms(steps) / 1000.0) * 1000.0
        + th.duration_max_slack_ms
        + 500.0
    )


# ── Suy luận trên chuỗi tư thế ────────────────────────────────────────


def pose_samples(observations: Sequence[Observation]) -> list[PoseSample]:
    samples: list[PoseSample] = []
    for index, obs in enumerate(observations):
        if obs.face is None:
            continue
        ratios = pose_ratios(obs.face)
        if ratios is not None:
            samples.append(PoseSample(obs.t_ms, ratios[0], ratios[1], index))
    return samples


def smooth(samples: list[PoseSample]) -> list[PoseSample]:
    """Trung vị 3 điểm để dập nhiễu một khung của bộ dò; chỉ khi lấy mẫu dày (≤ 200 ms)."""
    if len(samples) < 3:
        return samples
    steps = [b.t_ms - a.t_ms for a, b in itertools.pairwise(samples)]
    if statistics.median(steps) > 200.0:
        return samples
    smoothed = [samples[0]]
    for prev, cur, nxt in zip(samples, samples[1:], samples[2:]):
        smoothed.append(
            PoseSample(
                cur.t_ms,
                statistics.median((prev.r, cur.r, nxt.r)),
                statistics.median((prev.p, cur.p, nxt.p)),
                cur.index,
            )
        )
    smoothed.append(samples[-1])
    return smoothed


def compute_baseline(
    samples: Sequence[PoseSample], window: tuple[float, float] | None
) -> tuple[float, float] | None:
    """Mức nền ``(r0, p0)`` = trung vị trong đoạn FRONTAL đầu (dự phòng: 5 mẫu đầu)."""
    chosen: list[PoseSample] = []
    if window is not None:
        chosen = [s for s in samples if window[0] <= s.t_ms <= window[1]]
    if len(chosen) < 3:
        chosen = list(samples[:5])
    if not chosen:
        return None
    return (
        statistics.median(s.r for s in chosen),
        statistics.median(s.p for s in chosen),
    )


def normalized_deviation(
    sample: PoseSample, baseline: tuple[float, float], th: LivenessThresholds
) -> tuple[float, float]:
    """Độ lệch chuẩn hoá theo θ: ``nr`` > 0 = quay trái (đã tính ``yaw_invert``)."""
    sign = -1.0 if th.yaw_invert else 1.0
    return sign * (sample.r - baseline[0]) / th.theta_r, (
        sample.p - baseline[1]
    ) / th.theta_p


def _classify(nr: float, npitch: float) -> str:
    if abs(nr) >= abs(npitch):
        return TURN_LEFT if nr > 0 else TURN_RIGHT
    return LOOK_UP if npitch < 0 else LOOK_DOWN


def detect_events(
    samples: Sequence[PoseSample], baseline: tuple[float, float], th: LivenessThresholds
) -> list[Event]:
    """Máy trạng thái trễ hai ngưỡng: chính diện → lệch ≥ 1 (θ) → về < 0,5 (θ/2)."""
    events: list[Event] = []
    seen_frontal = False
    active = False
    peak_mag = 0.0
    peak: tuple[float, float, PoseSample] | None = None
    for sample in samples:
        nr, npitch = normalized_deviation(sample, baseline, th)
        magnitude = max(abs(nr), abs(npitch))
        if not active:
            if magnitude < 0.5:
                seen_frontal = True
            elif magnitude >= 1.0 and seen_frontal:
                active, peak_mag, peak = True, magnitude, (nr, npitch, sample)
            continue
        if magnitude > peak_mag:
            peak_mag, peak = magnitude, (nr, npitch, sample)
        if magnitude < 0.5 and peak is not None:
            events.append(
                Event(
                    _classify(peak[0], peak[1]),
                    peak[2].t_ms,
                    peak[2].index,
                    round(peak_mag, 3),
                )
            )
            active, seen_frontal, peak = False, True, None
    return events


def _lcs_length(a: Sequence[str], b: Sequence[str]) -> int:
    table = [[0] * (len(b) + 1) for _ in range(len(a) + 1)]
    for i, x in enumerate(a, start=1):
        for j, y in enumerate(b, start=1):
            table[i][j] = (
                table[i - 1][j - 1] + 1
                if x == y
                else max(table[i - 1][j], table[i][j - 1])
            )
    return table[-1][-1]


def compare_sequences(
    expected: Sequence[str], detected: Sequence[str]
) -> tuple[str, int]:
    """So **chặt**: chỉ bằng đúng mới là ``ACTIONS_MATCH``; trả kèm số sự kiện thừa."""
    if list(expected) == list(detected):
        return ACTIONS_MATCH, 0
    common = _lcs_length(expected, detected)
    extra = len(detected) - common
    missing = len(expected) - common
    if extra == 0:
        return ACTIONS_MISSING, 0
    if missing == 0:
        return ACTIONS_EXTRA, extra
    return ACTIONS_MISMATCH, extra


def intervals_ok(
    expected_times: Sequence[float],
    detected_times: Sequence[float],
    th: LivenessThresholds,
    extra_tolerance_ms: float = 0.0,
) -> bool:
    """Khoảng cách giữa hai đỉnh liên tiếp khớp lịch, sai số max(0,7 s; 35% khoảng)."""
    for i in range(1, min(len(expected_times), len(detected_times))):
        expected_gap = expected_times[i] - expected_times[i - 1]
        detected_gap = detected_times[i] - detected_times[i - 1]
        tolerance = max(
            th.interval_tol_s * 1000.0, th.interval_tol_ratio * expected_gap
        )
        if abs(detected_gap - expected_gap) > tolerance + extra_tolerance_ms:
            return False
    return True


def _iou(a: Face, b: Face) -> float:
    x0, y0 = max(a.x, b.x), max(a.y, b.y)
    x1, y1 = min(a.x + a.w, b.x + b.w), min(a.y + a.h, b.y + b.h)
    inter = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = a.area + b.area - inter
    return inter / union if union > 0 else 0.0


def track_continuous(observations: Sequence[Observation], sample_gap_ms: float) -> bool:
    """Mặt được bám liên tục: không mất mặt quá lâu, không "nhảy" sang vị trí khác hẳn."""
    previous: Observation | None = None
    max_gap = max(1000.0, 3.0 * sample_gap_ms)
    for obs in observations:
        if obs.face is None or obs.n_faces != 1:
            continue
        if previous is not None and previous.face is not None:
            if obs.t_ms - previous.t_ms > max_gap:
                return False
            a, b = previous.face, obs.face
            shift = math.hypot(
                (a.x + a.w / 2) - (b.x + b.w / 2), (a.y + a.h / 2) - (b.y + b.h / 2)
            )
            if _iou(a, b) < 0.1 and shift > 0.8 * max(a.w, b.w):
                return False
        previous = obs
    return True


def select_identity_frames(
    candidates: Sequence[Observation], count: int
) -> list[Observation]:
    """Tối đa ``count`` khung chính diện rải đều theo thời gian (khung nét nhất mỗi đoạn),
    luôn gồm khung nét nhất toàn video — chính khung sẽ dùng để so với chân dung."""
    pool = [c for c in candidates if c.sharpness is not None]
    if len(pool) <= count:
        return sorted(pool, key=lambda o: o.t_ms)
    start, end = pool[0].t_ms, pool[-1].t_ms
    width = (end - start) / count or 1.0
    bins: dict[int, Observation] = {}
    for obs in pool:
        slot = min(count - 1, int((obs.t_ms - start) / width))
        if slot not in bins or (obs.sharpness or 0.0) > (bins[slot].sharpness or 0.0):
            bins[slot] = obs
    sharpest = max(pool, key=lambda o: o.sharpness or 0.0)
    slot = min(count - 1, int((sharpest.t_ms - start) / width))
    bins[slot] = sharpest
    return sorted(bins.values(), key=lambda o: o.t_ms)


def decide(reasons: Sequence[str], mode: str) -> str:
    """RETAKE > FAIL > REVIEW > PASS. STILLS tối đa REVIEW."""
    reason_set = set(reasons)
    if reason_set & RETAKE_REASONS:
        return DECISION_RETAKE
    if reason_set & FAIL_REASONS:
        return DECISION_FAIL
    if mode == MODE_STILLS or reason_set & REVIEW_REASONS:
        return DECISION_REVIEW
    if ACTIONS_MATCH in reason_set and FACE_MATCH_HIGH in reason_set:
        return DECISION_PASS
    return DECISION_REVIEW


def _round(value: float | None, digits: int) -> float | None:
    return None if value is None else round(float(value), digits)


class _Reasons:
    """Danh sách lý do giữ thứ tự xuất hiện, không trùng."""

    def __init__(self) -> None:
        self.items: list[str] = []

    def add(self, code: str) -> None:
        if code not in self.items:
            self.items.append(code)

    def has_any(self, codes: frozenset[str]) -> bool:
        return any(code in codes for code in self.items)


def _video_block(meta: VideoMeta | None) -> dict | None:
    if meta is None:
        return None
    return {
        "duration_ms": round(meta.duration_ms),
        "fps": round(meta.fps, 1),
        "pts_monotonic": meta.pts_monotonic,
        "max_gap_ms": round(meta.max_gap_ms),
        "codec": meta.codec,
    }


def analyze_observations(
    *,
    mode: str,
    meta: VideoMeta | None,
    observations: Sequence[Observation],
    steps: Sequence[Step],
    portrait_embedding: NDArray,
    embed: Callable[[Observation], NDArray],
    render_evidence: Callable[[Observation], bytes],
    th: LivenessThresholds,
) -> dict:
    """Ra quyết định liveness từ các quan sát khung (hàm thuần, inject được để test)."""
    reasons = _Reasons()
    expected = expected_actions(steps)
    sampled = len(observations)
    response: dict = {
        "decision": DECISION_RETAKE,
        "reasons": [],
        "video": _video_block(meta) if mode == MODE_VIDEO else None,
        "faces": {
            "sampled_frames": sampled,
            "single_face_ratio": 0.0,
            "multi_face_frames": 0,
            "min_ied_px": None,
            "track_continuous": False,
        },
        "actions": {
            "expected": expected,
            "detected": [],
            "extra_events": 0,
            "intervals_ok": False,
        },
        "identity": {"min_pair_cos": None, "frames_checked": 0},
        "face_match": {"score": None, "zone": None},
        "quality": {"best_sharpness": None, "brightness": None},
        "evidence_frames": [],
        "thresholds_version": th.version,
    }

    def finish() -> dict:
        if mode == MODE_STILLS:
            reasons.add(STILLS_MODE)
        response["decision"] = decide(reasons.items, mode)
        response["reasons"] = list(reasons.items)
        return response

    # 1. Kiểm video (chế độ STILLS bỏ qua).
    if mode == MODE_VIDEO:
        if meta is None or not meta.readable or meta.frame_count == 0:
            reasons.add(VIDEO_UNREADABLE)
            return finish()
        if meta.duration_ms < total_hold_ms(steps) - th.duration_min_slack_ms:
            reasons.add(VIDEO_TOO_SHORT)
            return finish()
        if (
            not meta.pts_monotonic
            or meta.max_gap_ms > th.max_gap_ms
            or meta.fps < th.min_fps
        ):
            reasons.add(VIDEO_GAPS)
        if meta.encoder_tag:
            reasons.add(SOFT_ENCODER_TAG)
        if meta.duration_ms > max_duration_ms(steps, th):
            # Video dài hơn trần app tự dừng: không khớp nhịp thử thách (client lạ).
            reasons.add(INTERVALS_MISMATCH)

    # 2. Có mặt hay không, đủ sáng không.
    if sampled == 0:
        reasons.add(VIDEO_UNREADABLE if mode == MODE_VIDEO else NO_FACE)
        return finish()
    with_face = [o for o in observations if o.n_faces >= 1 and o.face is not None]
    single = [o for o in observations if o.n_faces == 1]
    multi = sum(1 for o in observations if o.n_faces >= 2)
    response["faces"]["single_face_ratio"] = round(len(single) / sampled, 3)
    response["faces"]["multi_face_frames"] = multi
    if len(with_face) / sampled < th.no_face_ratio:
        overall_brightness = statistics.median(o.brightness for o in observations)
        reasons.add(TOO_DARK if overall_brightness < th.min_brightness else NO_FACE)
        response["quality"]["brightness"] = _round(overall_brightness, 3)
        return finish()
    face_brightness = statistics.median(o.brightness for o in with_face)
    response["quality"]["brightness"] = _round(face_brightness, 3)
    if face_brightness < th.min_brightness:
        reasons.add(TOO_DARK)
        return finish()

    # 3. Tư thế, mức nền, khung chính diện.
    samples = smooth(pose_samples(observations))
    baseline = compute_baseline(samples, first_frontal_window(steps))
    if baseline is None:
        reasons.add(NO_FACE)
        return finish()
    frontal: list[Observation] = []
    for sample in samples:
        nr, npitch = normalized_deviation(sample, baseline, th)
        obs = observations[sample.index]
        if abs(nr) < 0.5 and abs(npitch) < 0.5 and obs.n_faces == 1:
            frontal.append(obs)
    ied_pool = [o.ied_px for o in (frontal or with_face) if o.ied_px is not None]
    if ied_pool:
        response["faces"]["min_ied_px"] = _round(min(ied_pool), 1)
        if statistics.median(ied_pool) < th.min_ied:
            reasons.add(FACE_TOO_SMALL)
            return finish()

    # 4. Nhiều người, bám mặt.
    if multi / sampled > th.max_multi_face_ratio:
        reasons.add(MULTIPLE_FACES)
    sample_gap = statistics.median(
        [b.t_ms - a.t_ms for a, b in itertools.pairwise(observations)] or [0.0]
    )
    continuous = track_continuous(observations, sample_gap)
    response["faces"]["track_continuous"] = continuous
    if not continuous or len(single) / sampled < th.min_single_face_ratio:
        reasons.add(TRACK_BROKEN)

    # 5. Hành động: khớp chặt + nhịp.
    events = detect_events(samples, baseline, th)
    detected = [e.action for e in events]
    code, extra = compare_sequences(expected, detected)
    response["actions"]["detected"] = detected
    response["actions"]["extra_events"] = extra
    if code == ACTIONS_MATCH:
        # Ảnh rời ~1 khung/giây: đỉnh lệch tối đa nửa khoảng lấy mẫu mỗi bên.
        slack = sample_gap if mode == MODE_STILLS else 0.0
        ok = intervals_ok(
            expected_peak_times(steps), [e.t_ms for e in events], th, slack
        )
        response["actions"]["intervals_ok"] = ok
        reasons.add(ACTIONS_MATCH)
        if not ok:
            reasons.add(INTERVALS_MISMATCH)
    else:
        reasons.add(code)

    # 6. Cùng người + 7. so mặt.
    identity_frames = select_identity_frames(frontal, th.identity_frames)
    if identity_frames:
        best = max(identity_frames, key=lambda o: o.sharpness or 0.0)
        response["quality"]["best_sharpness"] = _round(best.sharpness, 1)
        if (best.sharpness or 0.0) < th.min_sharpness:
            reasons.add(BLURRY)
        embeddings = [embed(o) for o in identity_frames]
        response["identity"]["frames_checked"] = len(identity_frames)
        if len(embeddings) >= 2:
            min_pair = min(
                cosine(embeddings[i], embeddings[j])
                for i in range(len(embeddings))
                for j in range(i + 1, len(embeddings))
            )
            response["identity"]["min_pair_cos"] = round(min_pair, 4)
            if min_pair < th.t_same:
                reasons.add(IDENTITY_INCONSISTENT)
        else:
            reasons.add(TRACK_BROKEN)
        score = cosine(embeddings[identity_frames.index(best)], portrait_embedding)
        zone = (
            ZONE_HIGH
            if score >= th.t_high
            else ZONE_GRAY
            if score >= th.t_low
            else ZONE_LOW
        )
        response["face_match"] = {"score": round(score, 4), "zone": zone}
        reasons.add(
            {
                ZONE_HIGH: FACE_MATCH_HIGH,
                ZONE_GRAY: FACE_MATCH_GRAY,
                ZONE_LOW: FACE_MISMATCH,
            }[zone]
        )
    else:
        reasons.add(TRACK_BROKEN)

    # 8. Khung bằng chứng: chính diện đầu + đỉnh mỗi hành động.
    evidence: list[tuple[str, Observation]] = []
    window = first_frontal_window(steps)
    opening = [o for o in frontal if window is None or o.t_ms <= window[1]] or frontal[
        :1
    ]
    if opening:
        evidence.append((FRONTAL, max(opening, key=lambda o: o.sharpness or 0.0)))
    for event in events:
        evidence.append((event.action, observations[event.index]))
    response["evidence_frames"] = [
        {"label": label, "jpeg_b64": _b64(render_evidence(obs))}
        for label, obs in evidence[:MAX_EVIDENCE_FRAMES]
        if obs.frame_index is not None
    ]
    return finish()


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


# ── Giải mã video / ảnh rời thành quan sát ────────────────────────────


class FrameStore:
    """Khung đã lấy mẫu lưu dạng JPEG trong RAM (~100 KB/khung thay vì ~1 MB pixel)."""

    def __init__(self) -> None:
        self._frames: list[bytes] = []

    def add(self, image: NDArray) -> int:
        self._frames.append(image_io.encode_jpeg(image, quality=92))
        return len(self._frames) - 1

    def get(self, index: int) -> NDArray:
        return image_io.decode_jpeg(self._frames[index])

    def __len__(self) -> int:
        return len(self._frames)


def _shrink(frame: NDArray) -> tuple[NDArray, float]:
    """Thu khung để cạnh dài ≤ ``WORK_MAX_SIDE``; trả kèm hệ số về kích thước gốc."""
    h, w = frame.shape[:2]
    longest = max(h, w)
    if longest <= WORK_MAX_SIDE:
        return frame, 1.0
    scale = WORK_MAX_SIDE / float(longest)
    small = cv2.resize(
        frame,
        (max(1, round(w * scale)), max(1, round(h * scale))),
        interpolation=cv2.INTER_AREA,
    )
    return small, 1.0 / scale


def _region_brightness(image: NDArray, face: Face | None) -> float:
    if face is None:
        return image_io.brightness(image)
    h, w = image.shape[:2]
    x0, y0 = max(0, int(face.x)), max(0, int(face.y))
    x1, y1 = min(w, int(face.x + face.w)), min(h, int(face.y + face.h))
    if x1 <= x0 or y1 <= y0:
        return image_io.brightness(image)
    return image_io.brightness(image[y0:y1, x0:x1])


class _FrameProcessor:
    """Đo một khung: dò mặt trên bản 480 px, đổi toạ độ về khung lưu và về ảnh gốc."""

    def __init__(self, engine: FaceEngine, th: LivenessThresholds, store: FrameStore):
        self.engine = engine
        self.th = th
        self.store = store
        self.rotation: int | None = None

    def _detect(self, work: NDArray) -> tuple[list[Face], float]:
        det = image_io.resize_to_width(work, self.th.detect_width)
        faces = self.engine.detect(det, self.th.min_face_score)
        if faces:
            largest = faces[0].w
            faces = [f for f in faces if f.w >= SECONDARY_FACE_MIN_RATIO * largest]
        return faces, work.shape[1] / float(det.shape[1])

    def count_faces(self, small: NDArray, rotation: int | None) -> int:
        work = cv2.rotate(small, rotation) if rotation is not None else small
        return 1 if self._detect(work)[0] else 0

    def process(self, small: NDArray, factor: float, t_ms: float) -> Observation:
        work = cv2.rotate(small, self.rotation) if self.rotation is not None else small
        faces, to_work = self._detect(work)
        if not faces:
            return Observation(
                t_ms, 0, None, None, image_io.brightness(work), None, None
            )
        main = faces[0].scaled(to_work)
        return Observation(
            t_ms=t_ms,
            n_faces=len(faces),
            face=main,
            ied_px=main.ied * factor,
            brightness=_region_brightness(work, main),
            sharpness=self.engine.face_sharpness(work, main),
            frame_index=self.store.add(work),
        )

    def decide_rotation(self, smalls: Sequence[NDArray]) -> None:
        """Gần như không thấy mặt ở ~1 giây đầu → thử một lần 90°/180°/270°, giữ góc
        tìm được nhiều mặt nhất (chỉ đổi khi tốt hơn hẳn chiều gốc)."""
        if not smalls:
            return
        base = sum(self.count_faces(s, None) for s in smalls)
        if base > ROTATION_TRIGGER_RATIO * len(smalls):
            return
        best_rotation, best = None, base
        for rotation in _ROTATION_DEGREES:
            hits = sum(self.count_faces(s, rotation) for s in smalls)
            if hits > best:
                best_rotation, best = rotation, hits
        self.rotation = best_rotation
        if best_rotation is not None:
            logger.info(
                "Liveness: video không áp metadata xoay — xoay %d° để dò mặt (%d/%d khung).",
                _ROTATION_DEGREES[best_rotation],
                best,
                len(smalls),
            )


def probe_frame_count(th: LivenessThresholds) -> int:
    """Số khung lấy mẫu ứng với ~1 giây đầu video."""
    return max(4, round(th.sample_fps * ROTATION_PROBE_SECONDS))


def has_soft_encoder_tag(data: bytes) -> bool:
    """File có dấu của phần mềm mã hoá/biên tập (ffmpeg, x264, HandBrake) — tín hiệu mềm."""
    return any(marker in data for marker in SOFT_ENCODER_MARKERS)


def _codec_name(fourcc_value: float) -> str | None:
    code = int(fourcc_value)
    if code <= 0:
        return None
    text = (
        "".join(chr((code >> (8 * i)) & 0xFF) for i in range(4)).strip("\x00 ").lower()
    )
    aliases = {
        "avc1": "h264",
        "h264": "h264",
        "x264": "h264",
        "hev1": "hevc",
        "hvc1": "hevc",
        "hevc": "hevc",
        "mp4v": "mpeg4",
        "fmp4": "mpeg4",
    }
    return aliases.get(text, text or None)


def observe_video(
    data: bytes, steps: Sequence[Step], engine: FaceEngine, th: LivenessThresholds
) -> tuple[VideoMeta, list[Observation], FrameStore]:
    """Giải mã video (file tạm vì ``VideoCapture`` cần đường dẫn) thành các quan sát."""
    store = FrameStore()
    processor = _FrameProcessor(engine, th, store)
    encoder_tag = has_soft_encoder_tag(data)
    stop_after_ms = max_duration_ms(steps, th) + 2000.0
    fd, path = tempfile.mkstemp(prefix="ekyc-liveness-", suffix=".mp4")
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
        capture = cv2.VideoCapture(path, cv2.CAP_FFMPEG)
        if not capture.isOpened():
            return (
                VideoMeta(False, 0.0, 0.0, False, 0.0, None, 0, encoder_tag),
                [],
                store,
            )
        try:
            capture.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
            codec = _codec_name(capture.get(cv2.CAP_PROP_FOURCC))
            nominal_fps = capture.get(cv2.CAP_PROP_FPS) or 0.0
            fallback_step = 1000.0 / nominal_fps if nominal_fps > 0 else 33.3
            interval = 1000.0 / th.sample_fps
            probe_frames = probe_frame_count(th)
            observations: list[Observation] = []
            pending: list[tuple[NDArray, float, float]] = []
            gaps: list[float] = []
            first_pts: float | None = None
            last_pts = 0.0
            last_t = 0.0
            monotonic = True
            next_sample = 0.0
            count = 0
            while count < MAX_DECODED_FRAMES:
                if not capture.grab():
                    break
                pts = capture.get(cv2.CAP_PROP_POS_MSEC)
                count += 1
                if first_pts is None:
                    first_pts, t_ms = pts, 0.0
                else:
                    gap = pts - last_pts
                    gaps.append(gap)
                    if gap <= 0:
                        # PTS hỏng: vẫn tiếp tục theo fps danh định để không mất phân tích.
                        monotonic = False
                        t_ms = last_t + fallback_step
                    else:
                        t_ms = pts - first_pts
                last_pts, last_t = pts, t_ms
                if t_ms > stop_after_ms:
                    break
                if t_ms + 1e-6 < next_sample:
                    continue
                while next_sample <= t_ms:
                    next_sample += interval
                ok, frame = capture.retrieve()
                if not ok or frame is None:
                    continue
                small, factor = _shrink(frame)
                if (
                    len(pending) < probe_frames
                    and processor.rotation is None
                    and not observations
                ):
                    pending.append((small, factor, t_ms))
                    if len(pending) == probe_frames:
                        processor.decide_rotation([p[0] for p in pending])
                        observations.extend(processor.process(*p) for p in pending)
                        pending = []
                    continue
                observations.append(processor.process(small, factor, t_ms))
            if pending:
                processor.decide_rotation([p[0] for p in pending])
                observations.extend(processor.process(*p) for p in pending)
        finally:
            capture.release()
        positive_gaps = [g for g in gaps if g > 0]
        frame_step = (
            statistics.median(positive_gaps) if positive_gaps else fallback_step
        )
        duration = last_t + frame_step if count > 0 else 0.0
        fps = (count - 1) / (last_t / 1000.0) if count > 1 and last_t > 0 else 0.0
        meta = VideoMeta(
            readable=count > 0,
            duration_ms=duration,
            fps=fps,
            pts_monotonic=monotonic,
            max_gap_ms=max(positive_gaps) if positive_gaps else 0.0,
            codec=codec,
            frame_count=count,
            encoder_tag=encoder_tag,
        )
        return meta, observations, store
    finally:
        try:
            os.remove(path)
        except OSError:
            logger.warning("Không xoá được file video tạm.")


def observe_stills(
    frames: Sequence[NDArray],
    times_ms: Sequence[float],
    engine: FaceEngine,
    th: LivenessThresholds,
) -> tuple[list[Observation], FrameStore]:
    """Chế độ STILLS: mỗi ảnh là một quan sát tại thời điểm client khai."""
    store = FrameStore()
    processor = _FrameProcessor(engine, th, store)
    shrunk = [_shrink(frame) for frame in frames]
    # Ảnh rời ~1 khung/giây: 1 giây chỉ có 1 ảnh nên dùng 4 ảnh đầu để đoán chiều xoay.
    processor.decide_rotation([s for s, _ in shrunk[:STILLS_PROBE_FRAMES]])
    origin = times_ms[0] if times_ms else 0.0
    observations = [
        processor.process(small, factor, float(t) - origin)
        for (small, factor), t in zip(shrunk, times_ms)
    ]
    return observations, store


def _store_callbacks(
    engine: FaceEngine, store: FrameStore
) -> tuple[Callable[[Observation], NDArray], Callable[[Observation], bytes]]:
    def embed(obs: Observation) -> NDArray:
        return engine.embed(store.get(obs.frame_index), obs.face)

    def render(obs: Observation) -> bytes:
        return image_io.encode_jpeg(
            store.get(obs.frame_index), EVIDENCE_QUALITY, width=EVIDENCE_WIDTH
        )

    return embed, render


def analyze_video(
    data: bytes,
    steps: Sequence[Step],
    portrait_embedding: NDArray,
    engine: FaceEngine,
    th: LivenessThresholds,
) -> dict:
    """Phân tích video liveness end-to-end."""
    meta, observations, store = observe_video(data, steps, engine, th)
    embed, render = _store_callbacks(engine, store)
    return analyze_observations(
        mode=MODE_VIDEO,
        meta=meta,
        observations=observations,
        steps=steps,
        portrait_embedding=portrait_embedding,
        embed=embed,
        render_evidence=render,
        th=th,
    )


def analyze_stills(
    frames: Sequence[NDArray],
    times_ms: Sequence[float],
    steps: Sequence[Step],
    portrait_embedding: NDArray,
    engine: FaceEngine,
    th: LivenessThresholds,
) -> dict:
    """Phân tích chế độ ảnh rời (máy không quay được video) — tối đa REVIEW."""
    observations, store = observe_stills(frames, times_ms, engine, th)
    embed, render = _store_callbacks(engine, store)
    return analyze_observations(
        mode=MODE_STILLS,
        meta=None,
        observations=observations,
        steps=steps,
        portrait_embedding=portrait_embedding,
        embed=embed,
        render_evidence=render,
        th=th,
    )
