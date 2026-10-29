"""Ngưỡng và cờ cấu hình eKYC — một chỗ duy nhất, đọc từ biến môi trường.

Mọi giá trị mặc định ở đây là **placeholder** (chưa có số đo): phải hiệu chỉnh bằng
``scripts/ekyc_calibrate.py`` và ``scripts/ekyc_eval_cccd.py`` trên dữ liệu tự thu.

``version`` được trả về trong mỗi response (``thresholds_version``) để mọi quyết định
lưu lại biết nó được chấm bằng bộ ngưỡng nào. Khi có biến ``EKYC_*`` nào ghi đè mà
không đổi tên phiên bản, phiên bản tự gắn đuôi ``+env-<hash>`` — tránh trường hợp
hai bộ ngưỡng khác nhau mang cùng một nhãn.
"""

from __future__ import annotations

import hashlib
import logging
import math
import os
from collections.abc import Mapping
from dataclasses import dataclass, fields, replace
from functools import lru_cache

logger = logging.getLogger(__name__)

DOC_THRESHOLDS_BASE_VERSION = "doc-2026-10-placeholder"
LIVENESS_THRESHOLDS_BASE_VERSION = "liveness-2026-10-placeholder"

GEMINI_MODE_OFF = "off"
GEMINI_MODE_FALLBACK = "fallback"

_TRUE_VALUES = ("1", "true", "yes", "on")


@dataclass(frozen=True)
class DocThresholds:
    """Ngưỡng cho ``POST /document`` (đọc CCCD)."""

    #: Contour thẻ phải chiếm ít nhất tỉ lệ này của ảnh, nhỏ hơn coi như không thấy thẻ.
    card_min_area_ratio: float = 0.20
    #: Chiều rộng thẻ sau khi nắn (px) — đủ để chân dung có IED ~70–90 px.
    card_warp_width: int = 1400
    #: Cổng chất lượng đo trên thẻ đưa về rộng cố định để Laplacian so sánh được.
    quality_width: int = 1000
    #: Phương sai Laplacian tối thiểu (đề xuất mục 2.5: khởi đầu 100).
    min_sharpness: float = 100.0
    #: Tỉ lệ pixel loá (kênh V > 250) tối đa (đề xuất: 2%).
    max_glare_ratio: float = 0.02
    #: Độ sáng trung bình tối thiểu (0..1).
    min_brightness: float = 0.25
    #: YuNet: điểm tối thiểu của khuôn mặt chân dung.
    portrait_min_score: float = 0.6
    #: Khoảng cách hai mắt tối thiểu (px, thẻ rộng 1400 px).
    portrait_min_ied: float = 40.0
    #: Độ nét tối thiểu của mặt chân dung (Laplacian trên crop căn chỉnh 112×112;
    #: đo thử: ảnh nét ~500–1250, mờ Gauss σ=3 ~30).
    portrait_min_sharpness: float = 40.0
    #: Chân dung nằm trong phần trái này của mặt trước.
    portrait_region_ratio: float = 0.45
    #: Số ký tự được lệch khi tìm số định danh in ở mặt trước.
    front_id_max_mismatch: int = 1
    version: str = DOC_THRESHOLDS_BASE_VERSION


@dataclass(frozen=True)
class LivenessThresholds:
    """Ngưỡng cho ``POST /liveness`` (kiểm tra người thật + so mặt)."""

    #: Ngưỡng lệch tỉ số quay đầu ``r`` so với mức nền.
    theta_r: float = 0.25
    #: Ngưỡng lệch tỉ số ngẩng/cúi ``p`` so với mức nền.
    theta_p: float = 0.15
    #: Cosine nhỏ nhất giữa các khung chính diện để coi là cùng một người.
    t_same: float = 0.5
    #: So mặt với chân dung: ≥ ``t_high`` là khớp, < ``t_low`` là không khớp, giữa là vùng xám.
    t_high: float = 0.50
    t_low: float = 0.25
    #: IED tối thiểu của mặt selfie (px, toạ độ gốc).
    min_ied: float = 40.0
    #: Tỉ lệ khung đúng 1 mặt tối thiểu để coi là bám mặt liên tục.
    min_single_face_ratio: float = 0.9
    #: Dưới tỉ lệ khung có mặt này thì không phân tích được → RETAKE NO_FACE.
    no_face_ratio: float = 0.5
    #: Tỉ lệ khung có từ 2 mặt trở lên tối đa trước khi coi là nhiều người.
    max_multi_face_ratio: float = 0.05
    #: Độ sáng tối thiểu (0..1) của vùng mặt.
    min_brightness: float = 0.25
    #: Độ nét tối thiểu của khung chính diện tốt nhất (Laplacian trên crop căn chỉnh).
    min_sharpness: float = 30.0
    #: fps tối thiểu của video.
    min_fps: float = 10.0
    #: Khoảng hở PTS lớn hơn ngưỡng này (ms) là dấu hiệu video bị cắt ghép.
    max_gap_ms: float = 250.0
    #: Thời lượng hợp lệ: [tổng hold − min_slack, tổng hold + max_slack] (ms).
    duration_min_slack_ms: float = 1000.0
    duration_max_slack_ms: float = 3000.0
    #: Sai số khoảng cách giữa hai đỉnh: max(interval_tol_s, interval_tol_ratio × khoảng kỳ vọng).
    interval_tol_s: float = 0.7
    interval_tol_ratio: float = 0.35
    #: Tần số lấy mẫu khung để chạy YuNet.
    sample_fps: float = 10.0
    #: Chiều rộng khung đưa vào YuNet (YuNet hợp mặt cỡ 10–300 px).
    detect_width: int = 480
    #: Điểm YuNet tối thiểu của một khuôn mặt trong video.
    min_face_score: float = 0.8
    #: Số khung chính diện tối đa dùng kiểm cùng người.
    identity_frames: int = 6
    #: Đảo dấu quay trái/phải khi video bị lật gương (mặc định cho mọi nền tảng).
    yaw_invert: bool = False
    #: Đảo dấu riêng theo nền tảng client (camera trước iOS có thể lật gương khác
    #: Android). Không đặt env riêng thì lấy theo ``yaw_invert``.
    yaw_invert_ios: bool = False
    yaw_invert_android: bool = False
    version: str = LIVENESS_THRESHOLDS_BASE_VERSION

    def yaw_invert_for(self, platform: str | None) -> bool:
        """Chiều quay áp cho video từ ``platform`` (``ios``/``android``/khác)."""
        if platform == "ios":
            return self.yaw_invert_ios
        if platform == "android":
            return self.yaw_invert_android
        return self.yaw_invert


@dataclass(frozen=True)
class EkycSettings:
    """Cờ nghiệp vụ của pipeline đọc thẻ (không phải ngưỡng số đo)."""

    #: Số lần được yêu cầu chụp lại trước khi chuyển lỗi dữ liệu sang duyệt tay.
    max_retakes: int = 2
    #: Cho AUTO_OK khi chỉ có một nguồn tất định + một nguồn đọc độc lập khớp.
    allow_single_deterministic: bool = False
    #: Thu thêm quê quán / nơi đăng ký khai sinh (mặc định tắt: giảm thiểu dữ liệu).
    optional_fields: bool = False
    #: ``off`` | ``fallback`` — Gemini chỉ điền chỗ trống.
    gemini_mode: str = GEMINI_MODE_FALLBACK
    #: Thời gian chờ tối đa một lần gọi Gemini (ms).
    gemini_timeout_ms: int = 15000
    #: Thời gian chờ tối đa một lần gọi Tesseract (giây).
    tesseract_timeout_s: float = 10.0
    #: Thư mục chứa model YuNet/SFace (rỗng = ``finora-ai/models/face``).
    face_model_dir: str = ""


_DOC_ENV: dict[str, str] = {
    "card_min_area_ratio": "EKYC_CARD_MIN_AREA_RATIO",
    "card_warp_width": "EKYC_CARD_WARP_WIDTH",
    "quality_width": "EKYC_QUALITY_WIDTH",
    "min_sharpness": "EKYC_QUALITY_MIN_SHARPNESS",
    "max_glare_ratio": "EKYC_QUALITY_MAX_GLARE",
    "min_brightness": "EKYC_QUALITY_MIN_BRIGHTNESS",
    "portrait_min_score": "EKYC_PORTRAIT_MIN_SCORE",
    "portrait_min_ied": "EKYC_PORTRAIT_MIN_IED",
    "portrait_min_sharpness": "EKYC_PORTRAIT_MIN_SHARPNESS",
    "portrait_region_ratio": "EKYC_PORTRAIT_REGION_RATIO",
    "front_id_max_mismatch": "EKYC_FRONT_ID_MAX_MISMATCH",
}

_LIVENESS_ENV: dict[str, str] = {
    "theta_r": "EKYC_LIVENESS_THETA_R",
    "theta_p": "EKYC_LIVENESS_THETA_P",
    "t_same": "EKYC_LIVENESS_T_SAME",
    "t_high": "EKYC_FACE_MATCH_T_HIGH",
    "t_low": "EKYC_FACE_MATCH_T_LOW",
    "min_ied": "EKYC_LIVENESS_MIN_IED",
    "min_single_face_ratio": "EKYC_LIVENESS_MIN_SINGLE_FACE_RATIO",
    "no_face_ratio": "EKYC_LIVENESS_NO_FACE_RATIO",
    "max_multi_face_ratio": "EKYC_LIVENESS_MAX_MULTI_FACE_RATIO",
    "min_brightness": "EKYC_LIVENESS_MIN_BRIGHTNESS",
    "min_sharpness": "EKYC_LIVENESS_MIN_SHARPNESS",
    "min_fps": "EKYC_LIVENESS_MIN_FPS",
    "max_gap_ms": "EKYC_LIVENESS_MAX_GAP_MS",
    "duration_min_slack_ms": "EKYC_LIVENESS_DURATION_MIN_SLACK_MS",
    "duration_max_slack_ms": "EKYC_LIVENESS_DURATION_MAX_SLACK_MS",
    "interval_tol_s": "EKYC_INTERVAL_TOL_S",
    "interval_tol_ratio": "EKYC_INTERVAL_TOL_RATIO",
    "sample_fps": "EKYC_LIVENESS_SAMPLE_FPS",
    "detect_width": "EKYC_LIVENESS_DETECT_WIDTH",
    "min_face_score": "EKYC_LIVENESS_MIN_FACE_SCORE",
    "identity_frames": "EKYC_LIVENESS_IDENTITY_FRAMES",
    "yaw_invert": "EKYC_LIVENESS_YAW_INVERT",
    "yaw_invert_ios": "EKYC_LIVENESS_YAW_INVERT_IOS",
    "yaw_invert_android": "EKYC_LIVENESS_YAW_INVERT_ANDROID",
}


def _parse(raw: str, kind: type) -> object:
    """Ép chuỗi env về đúng kiểu của trường; sai định dạng thì ném ``ValueError``."""
    text = raw.strip()
    if kind is bool:
        return text.lower() in _TRUE_VALUES
    if kind is int:
        return int(text)
    if kind is float:
        value = float(text)
        if math.isnan(value) or math.isinf(value):  # không bao giờ là ngưỡng hợp lệ
            raise ValueError(text)
        return value
    return text


def _load(
    cls: type,
    mapping: dict[str, str],
    env: Mapping[str, str],
    version_env: str,
    base: str,
):
    """Đọc các trường có env tương ứng; biến sai định dạng bị bỏ qua và log cảnh báo."""
    kinds = {f.name: type(f.default) for f in fields(cls)}
    values: dict[str, object] = {}
    overridden: dict[str, str] = {}
    for attr, env_name in mapping.items():
        raw = env.get(env_name)
        if raw is None or raw.strip() == "":
            continue
        try:
            values[attr] = _parse(raw, kinds[attr])
        except ValueError:
            logger.warning("Bỏ qua %s: giá trị không hợp lệ.", env_name)
            continue
        overridden[env_name] = raw.strip()

    version = (env.get(version_env) or "").strip()
    if not version:
        version = base
        if overridden:
            canonical = ";".join(f"{k}={v}" for k, v in sorted(overridden.items()))
            version += "+env-" + hashlib.sha256(canonical.encode()).hexdigest()[:8]
    return cls(**values, version=version)


def load_doc_thresholds(env: Mapping[str, str] | None = None) -> DocThresholds:
    """Ngưỡng đọc thẻ từ ``env`` (mặc định ``os.environ``)."""
    return _load(
        DocThresholds,
        _DOC_ENV,
        os.environ if env is None else env,
        "EKYC_DOC_THRESHOLDS_VERSION",
        DOC_THRESHOLDS_BASE_VERSION,
    )


def load_liveness_thresholds(
    env: Mapping[str, str] | None = None,
) -> LivenessThresholds:
    """Ngưỡng liveness từ ``env`` (mặc định ``os.environ``).

    ``EKYC_LIVENESS_YAW_INVERT_IOS``/``_ANDROID`` không đặt thì theo
    ``EKYC_LIVENESS_YAW_INVERT``.
    """
    env = os.environ if env is None else env
    loaded = _load(
        LivenessThresholds,
        _LIVENESS_ENV,
        env,
        "EKYC_LIVENESS_THRESHOLDS_VERSION",
        LIVENESS_THRESHOLDS_BASE_VERSION,
    )
    fallback = {}
    for attr in ("yaw_invert_ios", "yaw_invert_android"):
        if not (env.get(_LIVENESS_ENV[attr]) or "").strip():
            fallback[attr] = loaded.yaw_invert
    return replace(loaded, **fallback) if fallback else loaded


def _env_int(env: Mapping[str, str], name: str, default: int) -> int:
    try:
        return int(str(env.get(name, default)).strip())
    except ValueError:
        logger.warning("Bỏ qua %s: giá trị không hợp lệ.", name)
        return default


def _env_float(env: Mapping[str, str], name: str, default: float) -> float:
    try:
        return float(str(env.get(name, default)).strip())
    except ValueError:
        logger.warning("Bỏ qua %s: giá trị không hợp lệ.", name)
        return default


def _env_bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in _TRUE_VALUES


def load_settings(env: Mapping[str, str] | None = None) -> EkycSettings:
    """Cờ nghiệp vụ eKYC từ ``env`` (mặc định ``os.environ``)."""
    env = os.environ if env is None else env
    mode = (env.get("EKYC_GEMINI_MODE") or GEMINI_MODE_FALLBACK).strip().lower()
    if mode not in (GEMINI_MODE_OFF, GEMINI_MODE_FALLBACK):
        logger.warning("EKYC_GEMINI_MODE=%s không hợp lệ, dùng 'off'.", mode)
        mode = GEMINI_MODE_OFF
    return EkycSettings(
        max_retakes=max(0, _env_int(env, "EKYC_MAX_RETAKES", 2)),
        allow_single_deterministic=_env_bool(
            env, "EKYC_ALLOW_SINGLE_DETERMINISTIC", False
        ),
        optional_fields=_env_bool(env, "EKYC_OPTIONAL_FIELDS", False),
        gemini_mode=mode,
        gemini_timeout_ms=max(1000, _env_int(env, "EKYC_GEMINI_TIMEOUT_MS", 15000)),
        tesseract_timeout_s=max(1.0, _env_float(env, "EKYC_TESSERACT_TIMEOUT_S", 10.0)),
        face_model_dir=(env.get("EKYC_FACE_MODEL_DIR") or "").strip(),
    )


@lru_cache(maxsize=1)
def doc_thresholds() -> DocThresholds:
    """Bộ ngưỡng đọc thẻ đang chạy (đọc env một lần lúc dùng đầu tiên)."""
    return load_doc_thresholds()


@lru_cache(maxsize=1)
def liveness_thresholds() -> LivenessThresholds:
    """Bộ ngưỡng liveness đang chạy (đọc env một lần lúc dùng đầu tiên)."""
    return load_liveness_thresholds()


@lru_cache(maxsize=1)
def settings() -> EkycSettings:
    """Cờ nghiệp vụ eKYC đang chạy (đọc env một lần lúc dùng đầu tiên)."""
    return load_settings()
