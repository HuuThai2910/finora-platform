"""Schema của hai endpoint eKYC nội bộ (hợp đồng EKYC-002 mục 2).

Tên trường là hợp đồng với ``finora-user`` (đang được viết song song) — không đổi tên.
Ngày luôn ``dd/mm/yyyy``, giới tính luôn ``Nam``/``Nữ``.
"""

import itertools
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.ml.ekyc.image_io import MAX_IMAGE_B64_CHARS

CardSide = Literal["FRONT", "BACK"]
ImageSource = Literal["IN_APP_CAMERA", "SYSTEM_CAMERA", "GALLERY"]
FieldSource = Literal["QR", "MRZ", "OCR", "AI"]
CardType = Literal[
    "CCCD_CHIP_2021",
    "CAN_CUOC_2024",
    "CCCD_CHIP_UNKNOWN",
    "CCCD_BARCODE",
    "CMND",
    "UNKNOWN",
]
RetakeReason = Literal[
    "BLUR",
    "GLARE",
    "CARD_CUT",
    "TOO_DARK",
    "WRONG_SIDE",
    "MRZ_UNREADABLE",
    "QR_UNREADABLE",
    "PORTRAIT_UNUSABLE",
    "SOURCES_DISAGREE",
    "NO_DATA",
]
LivenessAction = Literal["FRONTAL", "TURN_LEFT", "TURN_RIGHT", "LOOK_UP", "LOOK_DOWN"]

#: Giới hạn đầu vào liveness (hợp đồng mục 2.2).
MAX_VIDEO_BYTES = 10 * 1024 * 1024
MAX_STILL_FRAMES = 20
MAX_STILL_FRAME_BYTES = 1_500_000


# ── /document ──────────────────────────────────────────────────────────


class DocumentRequest(BaseModel):
    """Hai ảnh thẻ + QR client đã quét (nếu có)."""

    model_config = ConfigDict(extra="ignore")

    front_image_base64: str = Field(..., min_length=1, max_length=MAX_IMAGE_B64_CHARS)
    back_image_base64: str = Field(..., min_length=1, max_length=MAX_IMAGE_B64_CHARS)
    qr_text: str | None = Field(default=None, max_length=2048)
    qr_side: CardSide | None = None
    front_source: ImageSource = "IN_APP_CAMERA"
    back_source: ImageSource = "IN_APP_CAMERA"
    retake_count: int = Field(default=0, ge=0, le=1000)


class FieldValue(BaseModel):
    value: str | None = None
    sources: list[FieldSource] = Field(default_factory=list)


class ExpiryFieldValue(FieldValue):
    no_expiry: bool = False


class DocumentFields(BaseModel):
    id_number: FieldValue
    full_name: FieldValue
    date_of_birth: FieldValue
    gender: FieldValue
    address: FieldValue
    place_of_origin: FieldValue
    birth_registration_place: FieldValue
    issue_date: FieldValue
    expiry_date: ExpiryFieldValue


class QrCheck(BaseModel):
    found: bool
    by: Literal["CLIENT", "SERVER"] | None
    side: CardSide | None
    valid: bool


class MrzCheck(BaseModel):
    found: bool
    valid: bool
    engine: Literal["TESSERACT", "GEMINI"] | None
    doc_number_ok: bool
    dob_ok: bool
    expiry_ok: bool
    composite_ok: bool
    id12_ok: bool


class DocumentChecks(BaseModel):
    qr: QrCheck
    mrz: MrzCheck
    id_rules_ok: bool
    sources_agree: bool
    front_back_linked: bool


class SideQualityInfo(BaseModel):
    card_found: bool
    sharpness: float
    glare_ratio: float
    brightness: float
    ok: bool


class DocumentQuality(BaseModel):
    front: SideQualityInfo
    back: SideQualityInfo


class PortraitInfo(BaseModel):
    """``embedding_b64`` = base64 của 128 float32 little-endian đã chuẩn hoá L2; chỉ có khi ``found``."""

    found: bool
    ied_px: float | None = None
    sharpness: float | None = None
    embedding_b64: str | None = None


class RetakeItem(BaseModel):
    side: CardSide
    reason: RetakeReason


class DocumentResponse(BaseModel):
    decision: Literal["AUTO_OK", "NEEDS_REVIEW", "RETAKE"]
    card_type: CardType
    reasons: list[str]
    warnings: list[str]
    retake: list[RetakeItem]
    fields: DocumentFields
    checks: DocumentChecks
    quality: DocumentQuality
    portrait: PortraitInfo
    ai_used: bool
    thresholds_version: str


# ── /liveness ──────────────────────────────────────────────────────────


class LivenessStepSpec(BaseModel):
    action: LivenessAction
    hold_ms: int = Field(..., ge=200, le=10_000)


class LivenessSpec(BaseModel):
    """Phần ``spec`` (JSON) của request multipart."""

    model_config = ConfigDict(extra="ignore")

    steps: list[LivenessStepSpec] = Field(..., min_length=2, max_length=16)
    portrait_embedding_b64: str = Field(..., min_length=1, max_length=2048)
    capture_mode: Literal["VIDEO", "STILLS"] = "VIDEO"
    frame_times_ms: list[int] | None = Field(default=None, max_length=MAX_STILL_FRAMES)
    #: Nền tảng app quay (``ios``/``android``/``web``) — chọn chiều quay trái/phải vì
    #: camera trước iOS và Android có thể lật gương khác nhau. Giá trị lạ coi như không có.
    platform: str | None = None

    @field_validator("platform", mode="before")
    @classmethod
    def _normalize_platform(cls, value: object) -> str | None:
        if value is None:
            return None
        text = str(value).strip().lower()
        return text if text in ("ios", "android", "web") else None

    @model_validator(mode="after")
    def _check_steps(self) -> "LivenessSpec":
        actions = [step.action for step in self.steps]
        if "FRONTAL" not in actions:
            raise ValueError("steps phải có ít nhất một bước FRONTAL (lấy mức nền)")
        if all(action == "FRONTAL" for action in actions):
            raise ValueError("steps phải có ít nhất một hành động")
        if self.capture_mode == "STILLS":
            times = self.frame_times_ms or []
            if not times:
                raise ValueError("STILLS cần frame_times_ms")
            if any(b <= a for a, b in itertools.pairwise(times)) or times[0] < 0:
                raise ValueError("frame_times_ms phải tăng dần, không âm")
        return self


class VideoInfo(BaseModel):
    duration_ms: int
    fps: float
    pts_monotonic: bool
    max_gap_ms: int
    codec: str | None


class FacesInfo(BaseModel):
    sampled_frames: int
    single_face_ratio: float
    multi_face_frames: int
    min_ied_px: float | None
    track_continuous: bool


class ActionsInfo(BaseModel):
    expected: list[LivenessAction]
    detected: list[LivenessAction]
    extra_events: int
    intervals_ok: bool


class IdentityInfo(BaseModel):
    min_pair_cos: float | None
    frames_checked: int


class FaceMatchInfo(BaseModel):
    score: float | None
    zone: Literal["HIGH", "GRAY", "LOW"] | None


class LivenessQuality(BaseModel):
    best_sharpness: float | None
    brightness: float | None


class EvidenceFrame(BaseModel):
    label: LivenessAction
    jpeg_b64: str


class LivenessResponse(BaseModel):
    decision: Literal["PASS", "REVIEW", "FAIL", "RETAKE"]
    reasons: list[str]
    video: VideoInfo | None
    faces: FacesInfo
    actions: ActionsInfo
    identity: IdentityInfo
    face_match: FaceMatchInfo
    quality: LivenessQuality
    evidence_frames: list[EvidenceFrame]
    thresholds_version: str
