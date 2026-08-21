"""Ngưỡng cấu hình cho eKYC — đọc từ biến môi trường, có mặc định an toàn.

Gom về một chỗ để chỉnh bằng env mà không phải sửa code, và để báo cáo có thể
trích dẫn đúng giá trị đang chạy.
"""

import os


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, default))
    except (TypeError, ValueError):
        return default


# ── OCR ───────────────────────────────────────────────────────────────

#: Chiều rộng chuẩn hoá ảnh CCCD trước khi OCR (px).
OCR_RESIZE_WIDTH = _int_env("EKYC_OCR_RESIZE_WIDTH", 1280)
