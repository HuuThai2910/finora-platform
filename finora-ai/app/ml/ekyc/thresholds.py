"""Ngưỡng cấu hình cho eKYC — đọc từ biến môi trường, có mặc định an toàn.

Gom về một chỗ để dò ngưỡng bằng ``scripts/ekyc_calibrate_liveness.py`` mà không
phải sửa code, và để báo cáo có thể trích dẫn đúng giá trị đang chạy.
"""

import os


def _float_env(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


# ── Passive liveness (LBP texture) ────────────────────────────────────

#: Ngưỡng variance của ảnh LBP — dưới ngưỡng coi là ảnh chụp lại từ màn hình.
LBP_VARIANCE_THRESHOLD = _float_env("EKYC_LBP_VARIANCE_THRESHOLD", 500.0)

#: Chiều rộng chuẩn hoá trước khi tính LBP (px) — giữ chi phí ổn định.
LBP_RESIZE_WIDTH = _int_env("EKYC_LBP_RESIZE_WIDTH", 480)

# ── Active liveness (nháy mắt / quay đầu) ─────────────────────────────

#: EAR dưới ngưỡng này coi như mắt đang nhắm.
EAR_CLOSED_THRESHOLD = _float_env("EKYC_EAR_CLOSED_THRESHOLD", 0.21)

#: EAR trên ngưỡng này coi như mắt đã mở lại — dùng để xác nhận đã "nháy".
EAR_OPEN_THRESHOLD = _float_env("EKYC_EAR_OPEN_THRESHOLD", 0.25)

#: Góc yaw (độ) tối thiểu để tính là đã quay đầu sang một bên.
YAW_TURN_DEGREES = _float_env("EKYC_YAW_TURN_DEGREES", 15.0)

#: Đảo dấu yaw khi camera client trả ảnh đã lật gương (selfie mirror).
#: Chạy ``scripts/ekyc_calibrate_liveness.py`` để biết camera của bạn có cần bật không.
YAW_INVERT = os.getenv("EKYC_YAW_INVERT", "false").strip().lower() in (
    "1",
    "true",
    "yes",
)

#: Góc yaw (độ) tối đa để coi khuôn mặt là chính diện.
YAW_FRONTAL_DEGREES = _float_env("EKYC_YAW_FRONTAL_DEGREES", 10.0)

#: Số frame tối thiểu cần có để kết luận — ít hơn thì không đủ dữ liệu thời gian.
MIN_FRAMES = _int_env("EKYC_MIN_FRAMES", 3)

#: Số frame tối đa xử lý — chặn request nhồi ảnh.
MAX_FRAMES = _int_env("EKYC_MAX_FRAMES", 20)

#: Chiều rộng chuẩn hoá frame trước khi đưa vào FaceMesh (px).
FRAME_RESIZE_WIDTH = _int_env("EKYC_FRAME_RESIZE_WIDTH", 640)

# ── OCR ───────────────────────────────────────────────────────────────

#: Chiều rộng chuẩn hoá ảnh CCCD trước khi OCR (px).
OCR_RESIZE_WIDTH = _int_env("EKYC_OCR_RESIZE_WIDTH", 1280)
