"""Active liveness — xác minh người dùng thực hiện đúng chuỗi hành động được yêu cầu.

Server sinh ngẫu nhiên chuỗi hành động (ví dụ ``["turn_left", "blink"]``), client
quay một đoạn ngắn rồi gửi các frame lên. Module này kiểm tra **đúng hành động,
đúng thứ tự** — đó là điểm chặn video quay sẵn, vì kẻ tấn công không đoán trước
được chuỗi hành động của phiên.

Thiết kế: phần đo đạc (MediaPipe FaceMesh) tách khỏi phần suy luận. Các hàm
suy luận ở cấp module đều thuần tuý trên :class:`FrameMetrics`, nên kiểm thử
được mà không cần MediaPipe hay ảnh thật.
"""

import logging
import math
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from app.ml.ekyc.image_io import require_cv2, resize_to_width, sharpness
from app.ml.ekyc.thresholds import (
    EAR_CLOSED_THRESHOLD,
    EAR_OPEN_THRESHOLD,
    FRAME_RESIZE_WIDTH,
    MAX_FRAMES,
    MIN_FRAMES,
    YAW_FRONTAL_DEGREES,
    YAW_INVERT,
    YAW_TURN_DEGREES,
)

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

try:
    import mediapipe as mp
except ImportError:
    mp = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

METHOD = "mediapipe_facemesh"

# Các hành động challenge được hỗ trợ.
BLINK = "blink"
TURN_LEFT = "turn_left"
TURN_RIGHT = "turn_right"
SUPPORTED_ACTIONS = (BLINK, TURN_LEFT, TURN_RIGHT)

# Chỉ số landmark FaceMesh cho công thức EAR (6 điểm mỗi mắt, theo thứ tự
# p1..p6: hai khoé mắt rồi hai cặp điểm mí trên/dưới).
RIGHT_EYE_EAR_IDX = (33, 160, 158, 133, 153, 144)
LEFT_EYE_EAR_IDX = (362, 385, 387, 263, 373, 380)

# 6 điểm dùng để ước lượng tư thế đầu: chóp mũi, cằm, khoé mắt ngoài trái/phải,
# hai khoé miệng — theo toạ độ ảnh (trái = x nhỏ).
POSE_LANDMARK_IDX = (1, 152, 33, 263, 61, 291)

# Mô hình 3D khuôn mặt chuẩn (mm) tương ứng POSE_LANDMARK_IDX.
MODEL_POINTS_3D = np.array(
    [
        (0.0, 0.0, 0.0),  # chóp mũi
        (0.0, -330.0, -65.0),  # cằm
        (-225.0, 170.0, -135.0),  # khoé mắt ngoài phía trái ảnh
        (225.0, 170.0, -135.0),  # khoé mắt ngoài phía phải ảnh
        (-150.0, -150.0, -125.0),  # khoé miệng phía trái ảnh
        (150.0, -150.0, -125.0),  # khoé miệng phía phải ảnh
    ],
    dtype=np.float64,
)

# Nới rộng bbox khuôn mặt thêm 10% mỗi chiều để không cắt sát viền.
BBOX_PADDING_RATIO = 0.1

Bbox = tuple[int, int, int, int]


@dataclass(frozen=True)
class FrameMetrics:
    """Số đo rút ra từ một frame. ``ear``/``yaw`` là ``None`` khi không thấy mặt."""

    index: int
    ear: float | None = None
    yaw: float | None = None
    sharpness: float = 0.0
    bbox: Bbox | None = None

    @property
    def has_face(self) -> bool:
        return self.ear is not None and self.yaw is not None


@dataclass(frozen=True)
class ActionEvent:
    """Một hành động đã hoàn tất trong chuỗi frame."""

    action: str
    start_index: int
    complete_index: int
    # EAR thấp nhất (blink) hoặc yaw lớn nhất theo trị tuyệt đối (turn).
    peak: float


# ── Đo đạc trên landmark ──────────────────────────────────────────────


def eye_aspect_ratio(points: NDArray) -> float:
    """EAR từ 6 điểm mắt (p1..p6): ``(|p2-p6| + |p3-p5|) / (2 * |p1-p4|)``.

    Mắt mở cho EAR khoảng 0.3; mắt nhắm tụt về gần 0.
    """
    p1, p2, p3, p4, p5, p6 = points
    horizontal = float(np.linalg.norm(p1 - p4))
    if horizontal <= 1e-6:
        return 0.0
    vertical = float(np.linalg.norm(p2 - p6) + np.linalg.norm(p3 - p5))
    return vertical / (2.0 * horizontal)


def estimate_yaw(image_points: NDArray, image_shape: tuple[int, int]) -> float | None:
    """Ước lượng góc yaw (độ) bằng solvePnP trên 6 điểm mốc.

    Quy ước dấu: **yaw âm = đầu quay về phía trái ảnh**, yaw dương = quay phải.
    Nếu camera client lật gương ảnh selfie, bật ``EKYC_YAW_INVERT=true``.
    """
    require_cv2()

    h, w = image_shape
    focal_length = float(w)
    camera_matrix = np.array(
        [[focal_length, 0, w / 2.0], [0, focal_length, h / 2.0], [0, 0, 1]],
        dtype=np.float64,
    )

    ok, rvec, _tvec = cv2.solvePnP(
        MODEL_POINTS_3D,
        image_points.astype(np.float64),
        camera_matrix,
        np.zeros((4, 1)),
        flags=cv2.SOLVEPNP_ITERATIVE,
    )
    if not ok:
        return None

    rmat, _ = cv2.Rodrigues(rvec)
    # RQDecomp3x3 trả góc Euler theo độ: (pitch, yaw, roll)
    angles = cv2.RQDecomp3x3(rmat)[0]
    yaw = float(angles[1])
    return -yaw if YAW_INVERT else yaw


# ── Suy luận trên chuỗi frame ─────────────────────────────────────────


def detect_blinks(metrics: list[FrameMetrics]) -> list[ActionEvent]:
    """Tìm các lần nháy mắt: mắt **mở → nhắm → mở lại**.

    Chỉ nhắm mà không mở lại thì không tính — đó là ảnh tĩnh nhắm mắt, không
    phải hành động sống.
    """
    events: list[ActionEvent] = []
    seen_open = False
    closed_at: int | None = None
    min_ear = 1.0

    for m in metrics:
        if m.ear is None:
            continue
        if m.ear > EAR_OPEN_THRESHOLD:
            if closed_at is not None:
                events.append(ActionEvent(BLINK, closed_at, m.index, round(min_ear, 4)))
                closed_at = None
                min_ear = 1.0
            seen_open = True
        elif m.ear < EAR_CLOSED_THRESHOLD and seen_open:
            if closed_at is None:
                closed_at = m.index
            min_ear = min(min_ear, m.ear)

    return events


def detect_turns(metrics: list[FrameMetrics]) -> list[ActionEvent]:
    """Tìm các lần quay đầu: **chính diện → vượt ngưỡng một bên → về chính diện**."""
    events: list[ActionEvent] = []
    seen_frontal = False
    turning: str | None = None
    turned_at: int | None = None
    peak = 0.0

    for m in metrics:
        if m.yaw is None:
            continue
        if abs(m.yaw) <= YAW_FRONTAL_DEGREES:
            if turning is not None and turned_at is not None:
                events.append(ActionEvent(turning, turned_at, m.index, round(peak, 2)))
                turning, turned_at, peak = None, None, 0.0
            seen_frontal = True
        elif seen_frontal and turning is None and abs(m.yaw) >= YAW_TURN_DEGREES:
            turning = TURN_LEFT if m.yaw < 0 else TURN_RIGHT
            turned_at = m.index
            peak = m.yaw
        elif turning is not None and abs(m.yaw) > abs(peak):
            peak = m.yaw

    return events


def match_sequence(expected: list[str], events: list[ActionEvent]) -> list[dict]:
    """Khớp chuỗi hành động yêu cầu với các sự kiện phát hiện được, **theo thứ tự**.

    Hành động thứ n phải hoàn tất ở frame sau hành động thứ n-1; không tìm được
    sự kiện phù hợp thì hành động đó trượt.
    """
    ordered = sorted(events, key=lambda e: e.complete_index)
    cursor = -1
    results: list[dict] = []

    for action in expected:
        match = next(
            (e for e in ordered if e.action == action and e.complete_index > cursor),
            None,
        )
        if match is None:
            results.append(
                {
                    "action": action,
                    "passed": False,
                    "evidence": _missing_evidence(action, cursor),
                    "margin": 0.0,
                }
            )
            continue

        cursor = match.complete_index
        results.append(
            {
                "action": action,
                "passed": True,
                "evidence": _found_evidence(match),
                "margin": _margin(match),
            }
        )

    return results


def select_best_frame(metrics: list[FrameMetrics]) -> FrameMetrics | None:
    """Chọn frame nét nhất trong số các frame có mặt **chính diện**.

    Không có frame chính diện nào thì lấy frame nét nhất còn thấy mặt — face
    match vẫn chạy được, chỉ kém chính xác hơn.
    """
    faces = [m for m in metrics if m.has_face]
    if not faces:
        return None
    # yaw == 0.0 là mặt chính diện hoàn hảo, không được coi là "thiếu giá trị"
    frontal = [
        m for m in faces if m.yaw is not None and abs(m.yaw) <= YAW_FRONTAL_DEGREES
    ]
    return max(frontal or faces, key=lambda m: m.sharpness)


def compute_confidence(results: list[dict]) -> float:
    """Confidence tổng hợp: đạt hết thì lấy theo hành động sát ngưỡng nhất."""
    if not results:
        return 0.0
    passed = [r for r in results if r["passed"]]
    if len(passed) < len(results):
        # Chưa đạt — confidence phản ánh tỉ lệ hoàn thành, luôn dưới 0.5
        return round(len(passed) / len(results) * 0.5, 4)
    return round(min(1.0, 0.5 + 0.5 * min(r["margin"] for r in passed)), 4)


def _margin(event: ActionEvent) -> float:
    """Mức vượt ngưỡng, chuẩn hoá về 0-1 — dùng để tính confidence."""
    if event.action == BLINK:
        raw = (EAR_OPEN_THRESHOLD - event.peak) / EAR_OPEN_THRESHOLD
    else:
        raw = abs(event.peak) / (2.0 * YAW_TURN_DEGREES)
    return round(max(0.0, min(1.0, raw)), 4)


def _found_evidence(event: ActionEvent) -> str:
    if event.action == BLINK:
        return (
            f"nháy mắt từ frame {event.start_index} đến {event.complete_index}, "
            f"EAR thấp nhất {event.peak}"
        )
    return (
        f"quay đầu từ frame {event.start_index}, yaw {event.peak} độ, "
        f"về chính diện ở frame {event.complete_index}"
    )


def _missing_evidence(action: str, cursor: int) -> str:
    after = "" if cursor < 0 else f" sau frame {cursor}"
    if action == BLINK:
        return f"không thấy nháy mắt (mở → nhắm → mở lại){after}"
    huong = "trái" if action == TURN_LEFT else "phải"
    return f"không thấy quay đầu sang {huong} rồi về chính diện{after}"


# ── Lớp đo đạc (có phụ thuộc MediaPipe) ───────────────────────────────


class ActiveLivenessDetector:
    """Đo landmark từng frame rồi kiểm tra chuỗi hành động."""

    def __init__(self):
        self._mesh = None

    def _get_mesh(self):
        if self._mesh is None:
            if mp is None:
                raise RuntimeError("mediapipe chưa được cài đặt.")
            logger.info("Khởi tạo MediaPipe FaceMesh...")
            self._mesh = mp.solutions.face_mesh.FaceMesh(
                static_image_mode=True,
                max_num_faces=1,
                refine_landmarks=False,
                min_detection_confidence=0.5,
            )
            logger.info("MediaPipe FaceMesh sẵn sàng.")
        return self._mesh

    def analyze(self, images: list[NDArray], expected_actions: list[str]) -> dict:
        """Kiểm tra chuỗi frame có thực hiện đúng ``expected_actions`` không.

        Args:
            images: Danh sách frame BGR theo đúng thứ tự thời gian.
            expected_actions: Chuỗi hành động của phiên challenge.

        Returns:
            dict với các khoá: ``is_live``, ``actions``, ``confidence``,
            ``method``, ``best_frame_index``, ``best_frame_bbox``.
        """
        unknown = [a for a in expected_actions if a not in SUPPORTED_ACTIONS]
        if not expected_actions or unknown:
            raise ValueError(f"Hành động không hỗ trợ: {unknown}")

        if len(images) > MAX_FRAMES:
            logger.warning(
                "Nhận %d frame, chỉ xử lý %d frame đầu.", len(images), MAX_FRAMES
            )
        frames = images[:MAX_FRAMES]

        metrics = [self.measure(img, i) for i, img in enumerate(frames)]
        faces = [m for m in metrics if m.has_face]

        if len(faces) < MIN_FRAMES:
            logger.info(
                "Chỉ %d/%d frame thấy khuôn mặt — không đủ để kết luận.",
                len(faces),
                len(frames),
            )
            evidence = f"chỉ {len(faces)}/{len(frames)} frame thấy khuôn mặt"
            return {
                "is_live": False,
                "actions": [
                    {"action": a, "passed": False, "evidence": evidence}
                    for a in expected_actions
                ],
                "confidence": 0.0,
                "method": METHOD,
                "best_frame_index": None,
                "best_frame_bbox": None,
            }

        events = detect_blinks(metrics) + detect_turns(metrics)
        results = match_sequence(list(expected_actions), events)
        best = select_best_frame(metrics)

        is_live = all(r["passed"] for r in results)
        logger.info(
            "Active liveness: actions=%s is_live=%s best_frame=%s",
            [(r["action"], r["passed"]) for r in results],
            is_live,
            best.index if best else None,
        )

        return {
            "is_live": is_live,
            "actions": [
                {
                    "action": r["action"],
                    "passed": r["passed"],
                    "evidence": r["evidence"],
                }
                for r in results
            ],
            "confidence": compute_confidence(results),
            "method": METHOD,
            "best_frame_index": best.index if best else None,
            "best_frame_bbox": best.bbox if best else None,
        }

    def measure(self, image: NDArray, index: int) -> FrameMetrics:
        """Đo EAR, yaw, độ nét và bbox khuôn mặt của một frame."""
        try:
            frame = resize_to_width(image, FRAME_RESIZE_WIDTH)
            h, w = frame.shape[:2]
            scale = image.shape[1] / w  # đưa bbox về toạ độ ảnh gốc

            rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            result = self._get_mesh().process(rgb)
            faces = getattr(result, "multi_face_landmarks", None)
            if not faces:
                return FrameMetrics(index=index)

            points = np.array(
                [(lm.x * w, lm.y * h) for lm in faces[0].landmark], dtype=np.float64
            )
            ear = (
                eye_aspect_ratio(points[list(LEFT_EYE_EAR_IDX)])
                + eye_aspect_ratio(points[list(RIGHT_EYE_EAR_IDX)])
            ) / 2.0
            yaw = estimate_yaw(points[list(POSE_LANDMARK_IDX)], (h, w))
            if yaw is None:
                return FrameMetrics(index=index)

            return FrameMetrics(
                index=index,
                ear=round(ear, 4),
                yaw=round(yaw, 2),
                sharpness=sharpness(frame),
                bbox=self._bbox(points, scale, image.shape[:2]),
            )

        except Exception:
            logger.exception("Lỗi khi đo landmark frame %d.", index)
            return FrameMetrics(index=index)

    @staticmethod
    def _bbox(points: NDArray, scale: float, image_shape: tuple[int, int]) -> Bbox:
        """Bbox khuôn mặt theo toạ độ ảnh gốc, nới thêm biên an toàn."""
        h, w = image_shape
        x0, y0 = points[:, 0].min() * scale, points[:, 1].min() * scale
        x1, y1 = points[:, 0].max() * scale, points[:, 1].max() * scale
        pad_x = (x1 - x0) * BBOX_PADDING_RATIO
        pad_y = (y1 - y0) * BBOX_PADDING_RATIO
        x0 = max(0.0, x0 - pad_x)
        y0 = max(0.0, y0 - pad_y)
        x1 = min(float(w), x1 + pad_x)
        y1 = min(float(h), y1 + pad_y)
        return int(x0), int(y0), math.ceil(x1 - x0), math.ceil(y1 - y0)
