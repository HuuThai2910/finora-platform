"""Phát hiện giả mạo tài liệu — ELA (Error Level Analysis) + EXIF metadata check.

ELA: Nén JPEG quality 95 → so sánh với ảnh gốc → vùng chỉnh sửa có error
level khác biệt rõ rệt so với phần còn lại.

EXIF: Kiểm tra metadata — phát hiện phần mềm chỉnh sửa (Photoshop, GIMP),
mâu thuẫn ngày, thiếu EXIF.
"""

import io
import logging
from typing import Any

import numpy as np

try:
    import cv2
except ImportError:
    cv2 = None  # type: ignore[assignment]

try:
    from PIL import Image
    from PIL.ExifTags import TAGS
except ImportError:
    Image = None  # type: ignore[assignment,misc]
    TAGS = {}  # type: ignore[assignment]

logger = logging.getLogger(__name__)

# Ngưỡng ELA — vùng chỉnh sửa thường có mean error > threshold
ELA_THRESHOLD = 15.0
ELA_JPEG_QUALITY = 95

# Phần mềm chỉnh sửa thường gặp
EDITING_SOFTWARE = [
    "photoshop", "gimp", "lightroom", "snapseed", "pixlr",
    "canva", "affinity", "paint.net", "fotor", "picsart",
]


def ela_analysis(image_bytes: bytes) -> dict:
    """Error Level Analysis — phát hiện vùng đã chỉnh sửa.

    Returns:
        dict: {
            "is_tampered": bool,
            "max_error": float,
            "mean_error": float,
            "suspicious_area_pct": float,  # % diện tích đáng ngờ
        }
    """
    fail_result = {
        "is_tampered": False, "max_error": 0.0,
        "mean_error": 0.0, "suspicious_area_pct": 0.0,
    }

    try:
        if cv2 is None:
            raise RuntimeError("opencv chưa cài đặt")

        # Decode ảnh gốc
        nparr = np.frombuffer(image_bytes, np.uint8)
        original = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if original is None:
            return fail_result

        # Nén lại JPEG quality 95
        encode_param = [int(cv2.IMWRITE_JPEG_QUALITY), ELA_JPEG_QUALITY]
        _, compressed_buf = cv2.imencode(".jpg", original, encode_param)
        compressed = cv2.imdecode(
            np.frombuffer(compressed_buf.tobytes(), np.uint8),
            cv2.IMREAD_COLOR,
        )

        # Tính error map
        diff = cv2.absdiff(original, compressed).astype(np.float64)
        error_map = np.mean(diff, axis=2)  # Average across BGR channels

        max_error = float(np.max(error_map))
        mean_error = float(np.mean(error_map))

        # Tính % diện tích đáng ngờ (pixels có error > threshold)
        suspicious_pixels = np.sum(error_map > ELA_THRESHOLD)
        total_pixels = error_map.shape[0] * error_map.shape[1]
        suspicious_pct = round(suspicious_pixels / total_pixels * 100, 2)

        is_tampered = bool(suspicious_pct > 5.0)  # > 5% diện tích bất thường

        return {
            "is_tampered": is_tampered,
            "max_error": round(max_error, 2),
            "mean_error": round(mean_error, 2),
            "suspicious_area_pct": suspicious_pct,
        }

    except Exception:
        logger.exception("Lỗi khi phân tích ELA.")
        return fail_result


def metadata_check(image_bytes: bytes) -> dict:
    """Kiểm tra EXIF metadata — phát hiện phần mềm chỉnh sửa.

    Returns:
        dict: {
            "flags": list[str],  # Mã cảnh báo
            "details": dict,     # Chi tiết EXIF
        }
    """
    flags: list[str] = []
    details: dict[str, Any] = {}

    try:
        if Image is None:
            raise RuntimeError("Pillow chưa cài đặt")

        img = Image.open(io.BytesIO(image_bytes))
        exif_data = img.getexif()

        if not exif_data:
            flags.append("MISSING_EXIF")
            return {"flags": flags, "details": details}

        # Parse EXIF tags
        for tag_id, value in exif_data.items():
            tag_name = TAGS.get(tag_id, str(tag_id))
            if isinstance(value, bytes):
                continue  # Skip binary data
            details[tag_name] = str(value)

        # Kiểm tra phần mềm chỉnh sửa
        software = details.get("Software", "").lower()
        if software:
            for editor in EDITING_SOFTWARE:
                if editor in software:
                    flags.append("EDITED_BY_SOFTWARE")
                    break

        # Kiểm tra thiếu camera model (ảnh chụp thật thường có)
        if "Model" not in details and "Make" not in details:
            flags.append("NO_CAMERA_INFO")

    except Exception:
        logger.exception("Lỗi khi kiểm tra EXIF metadata.")
        flags.append("EXIF_READ_ERROR")

    return {"flags": flags, "details": details}


def combined_verdict(image_bytes: bytes) -> dict:
    """Kết hợp ELA + EXIF → phán định cuối cùng.

    Returns:
        dict: {
            "is_tampered": bool,
            "confidence": float (0-1),
            "ela": dict,
            "metadata": dict,
        }
    """
    ela = ela_analysis(image_bytes)
    meta = metadata_check(image_bytes)

    confidence = 0.0
    tampered_signals = 0

    # ELA signal
    if ela["is_tampered"]:
        tampered_signals += 1
        confidence += 0.5

    # EXIF signals
    if "EDITED_BY_SOFTWARE" in meta["flags"]:
        tampered_signals += 1
        confidence += 0.3

    if "MISSING_EXIF" in meta["flags"]:
        tampered_signals += 1
        confidence += 0.15

    if "NO_CAMERA_INFO" in meta["flags"]:
        confidence += 0.05

    confidence = min(1.0, confidence)
    is_tampered = tampered_signals >= 1

    return {
        "is_tampered": is_tampered,
        "confidence": round(confidence, 4),
        "ela": ela,
        "metadata": meta,
    }
