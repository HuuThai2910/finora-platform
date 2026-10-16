"""Hợp nhất các nguồn đọc CCCD và ra quyết định ``AUTO_OK | NEEDS_REVIEW | RETAKE``.

Nguyên tắc (hợp đồng EKYC-002 mục 2.1 bước 10):

- Nguồn **tất định** (QR, MRZ có check digit) hỏng thì lộ ra; AI (Gemini) chỉ điền chỗ
  trống và không bao giờ một mình cho ``AUTO_OK``.
- ``RETAKE`` (khi ``retake_count < EKYC_MAX_RETAKES``): chân dung không dùng được (luôn
  RETAKE vì không so mặt được); mặt trước mờ/loá/tối mà QR mặt trước không đọc được;
  mặt sau mờ/loá/tối mà MRZ không hợp lệ; MRZ thấy nhưng sai check digit; QR và MRZ
  mâu thuẫn số/ngày sinh/giới tính; không có số định danh từ nguồn nào.
- Hết lượt chụp lại: mâu thuẫn → ``NEEDS_REVIEW`` (``SOURCES_DISAGREE_PERSISTENT``, giá
  trị theo MRZ vì có check digit); không có số định danh → vẫn ``RETAKE NO_DATA``.
- ``AUTO_OK``: QR hợp lệ **và** MRZ đủ check digit, khớp số + ngày sinh + giới tính; luật
  số khớp; hai mặt gắn được; chất lượng đạt; không mặt nào từ thư viện ảnh.
- Còn lại → ``NEEDS_REVIEW``. Luật nghiệp vụ (tuổi, hạn thẻ, CMND, trùng số) thuộc
  ``finora-user``, không quyết ở đây.

"Gắn hai mặt" chỉ tính bằng chứng **lấy từ chính ảnh**: QR do server tự giải trên ảnh
mặt trước, hoặc số định danh in ở mặt trước (Tesseract/Gemini). Chuỗi QR client gửi lên
không chứng minh nó nằm trên tấm ảnh mặt trước nên không đủ để gắn.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from app.ml.ekyc import id_rules
from app.ml.ekyc.front_id_ocr import edit_distance
from app.ml.ekyc.mrz import MrzRead, checks_dict
from app.ml.ekyc.qr_reader import SIDE_BACK, SIDE_FRONT, QrPayload
from app.ml.ekyc.quality import SideQuality
from app.ml.ekyc.thresholds import EkycSettings
from app.ml.ekyc.vn_text import format_date, normalize_name

DECISION_AUTO_OK = "AUTO_OK"
DECISION_NEEDS_REVIEW = "NEEDS_REVIEW"
DECISION_RETAKE = "RETAKE"

SOURCE_QR = "QR"
SOURCE_MRZ = "MRZ"
SOURCE_OCR = "OCR"
SOURCE_AI = "AI"
SOURCE_GALLERY = "GALLERY"
BY_CLIENT = "CLIENT"
BY_SERVER = "SERVER"

CARD_CCCD_CHIP_2021 = "CCCD_CHIP_2021"
CARD_CAN_CUOC_2024 = "CAN_CUOC_2024"
CARD_CCCD_CHIP_UNKNOWN = "CCCD_CHIP_UNKNOWN"
CARD_CCCD_BARCODE = "CCCD_BARCODE"
CARD_CMND = "CMND"
CARD_UNKNOWN = "UNKNOWN"

# Lý do chụp lại (retake[].reason) — finora-user ánh xạ sang thông điệp tiếng Việt.
RETAKE_BLUR = "BLUR"
RETAKE_GLARE = "GLARE"
RETAKE_CARD_CUT = "CARD_CUT"
RETAKE_TOO_DARK = "TOO_DARK"
RETAKE_WRONG_SIDE = "WRONG_SIDE"
RETAKE_MRZ_UNREADABLE = "MRZ_UNREADABLE"
RETAKE_QR_UNREADABLE = "QR_UNREADABLE"
RETAKE_PORTRAIT_UNUSABLE = "PORTRAIT_UNUSABLE"
RETAKE_SOURCES_DISAGREE = "SOURCES_DISAGREE"
RETAKE_NO_DATA = "NO_DATA"

# Cờ mềm (warnings): mỗi cờ đẩy hồ sơ về duyệt tay, không bao giờ RETAKE/từ chối.
WARN_PROVINCE_UNKNOWN = id_rules.WARN_PROVINCE_UNKNOWN
WARN_STRUCTURE_MISMATCH = id_rules.WARN_STRUCTURE_MISMATCH
WARN_FULL_NAME_MISMATCH = "FULL_NAME_MISMATCH"
WARN_QR_CLIENT_MISMATCH = "QR_CLIENT_MISMATCH"
WARN_MRZ_ID12_CHECK_FAILED = "MRZ_ID12_CHECK_FAILED"
WARN_EXPIRY_MILESTONE_MISMATCH = "EXPIRY_MILESTONE_MISMATCH"

# Mã lý do của quyết định (reasons) — không chứa PII, lưu được vào nhật ký duyệt.
REASON_QR_MRZ_AGREE = "QR_MRZ_AGREE"
REASON_SINGLE_SOURCE_CONFIRMED = "SINGLE_SOURCE_CONFIRMED"
REASON_ID_RULES_OK = "ID_RULES_OK"
REASON_FRONT_BACK_LINKED = "FRONT_BACK_LINKED"
REASON_QUALITY_OK = "QUALITY_OK"
REASON_PORTRAIT_OK = "PORTRAIT_OK"
REASON_QR_MISSING = "QR_MISSING"
REASON_MRZ_MISSING = "MRZ_MISSING"
REASON_SINGLE_SOURCE = "SINGLE_SOURCE"
REASON_SOURCES_INCOMPLETE = "SOURCES_INCOMPLETE"
REASON_AI_ONLY = "AI_ONLY"
REASON_NOT_LINKED = "NOT_LINKED"
REASON_QUALITY_LOW = "QUALITY_LOW"
REASON_GALLERY_SOURCE = "GALLERY_SOURCE"
REASON_CARD_BARCODE = "CARD_BARCODE"
REASON_CARD_CMND = "CARD_CMND"
REASON_CARD_UNKNOWN = "CARD_UNKNOWN"
REASON_SOFT_FLAGS = "SOFT_FLAGS"
REASON_ID_RULES_FAILED = "ID_RULES_FAILED"
REASON_EXPIRY_UNREADABLE = "EXPIRY_UNREADABLE"
REASON_NO_EXPIRY_UNVERIFIED = "NO_EXPIRY_UNVERIFIED"
REASON_SOURCES_DISAGREE_PERSISTENT = "SOURCES_DISAGREE_PERSISTENT"

_KIND_PORTRAIT = "portrait"
_KIND_NO_DATA = "no_data"
_KIND_OTHER = "other"


@dataclass(frozen=True)
class QrEvidence:
    """QR từ hai phía: chuỗi client quét sống và kết quả server tự giải trên ảnh."""

    client_text_present: bool = False
    client_payload: QrPayload | None = None
    client_side: str | None = None
    server_found: bool = False
    server_payload: QrPayload | None = None
    server_side: str | None = None


@dataclass(frozen=True)
class AiFields:
    """Giá trị Gemini đọc từ crop đã che chân dung (đã chuẩn hoá)."""

    called: bool = False
    card_title: str | None = None
    id_number: str | None = None
    full_name: str | None = None
    date_of_birth: date | None = None
    gender: str | None = None
    address: str | None = None
    place_of_origin: str | None = None
    birth_registration_place: str | None = None
    issue_date: date | None = None
    expiry_date: date | None = None
    no_expiry: bool = False
    front_id: str | None = None


@dataclass(frozen=True)
class PortraitSignal:
    found: bool
    wrong_side: bool = False


@dataclass(frozen=True)
class DocumentSignals:
    """Toàn bộ bằng chứng đã đo của một lượt quét — đầu vào thuần cho ``fuse``."""

    front_quality: SideQuality
    back_quality: SideQuality
    portrait: PortraitSignal
    qr: QrEvidence = field(default_factory=QrEvidence)
    mrz: MrzRead = field(
        default_factory=lambda: MrzRead(found=False, result=None, engine=None)
    )
    ocr_front_match: bool | None = None
    ai: AiFields = field(default_factory=AiFields)
    front_source: str = "IN_APP_CAMERA"
    back_source: str = "IN_APP_CAMERA"
    retake_count: int = 0


@dataclass
class FusionResult:
    decision: str
    card_type: str
    reasons: list[str]
    warnings: list[str]
    retake: list[dict]
    fields: dict
    checks: dict


class _Codes:
    def __init__(self) -> None:
        self.items: list[str] = []

    def add(self, code: str) -> None:
        if code not in self.items:
            self.items.append(code)


def _field(value, sources: list[str]) -> dict:
    return {"value": value, "sources": sources}


def names_match(a: str, b: str, b_truncated: bool = False) -> bool:
    """Tên có dấu (QR/AI) so với tên MRZ không dấu; MRZ có thể bị cắt ở 30 ký tự."""
    left, right = normalize_name(a), normalize_name(b)
    if not left or not right:
        return False
    if left == right:
        return True
    if b_truncated and left.startswith(right):
        return True
    return len(right) >= 8 and edit_distance(left, right, 1) <= 1


def _choose_qr(
    qr: QrEvidence, warnings: _Codes
) -> tuple[QrPayload | None, str | None, str | None]:
    """QR server giải trên ảnh được ưu tiên; client chỉ dùng khi server không giải được."""
    server, client = qr.server_payload, qr.client_payload
    if (
        server is not None
        and client is not None
        and (
            (server.id_number, server.date_of_birth, server.gender)
            != (client.id_number, client.date_of_birth, client.gender)
        )
    ):
        warnings.add(WARN_QR_CLIENT_MISMATCH)
    if server is not None:
        return server, BY_SERVER, qr.server_side
    if client is not None:
        return client, BY_CLIENT, qr.client_side
    if qr.server_found:
        return None, BY_SERVER, qr.server_side
    if qr.client_text_present:
        return None, BY_CLIENT, qr.client_side
    return None, None, None


def _pick(qr_value, mrz_value, ai_value) -> tuple[object, list[str], bool]:
    """Giá trị + nguồn cho số/ngày sinh/giới tính; trả kèm cờ QR–MRZ mâu thuẫn."""
    if qr_value is not None and mrz_value is not None:
        if qr_value == mrz_value:
            value, sources, conflict = qr_value, [SOURCE_QR, SOURCE_MRZ], False
        else:
            # MRZ có check digit và được đọc từ chính ảnh mặt sau → tin MRZ hơn.
            value, sources, conflict = mrz_value, [SOURCE_MRZ], True
    elif qr_value is not None:
        value, sources, conflict = qr_value, [SOURCE_QR], False
    elif mrz_value is not None:
        value, sources, conflict = mrz_value, [SOURCE_MRZ], False
    elif ai_value is not None:
        return ai_value, [SOURCE_AI], False
    else:
        return None, [], False
    if ai_value is not None and ai_value == value:
        sources.append(SOURCE_AI)
    return value, sources, conflict


def _card_type(
    qr: QrPayload | None, qr_side: str | None, mrz_found: bool, ai: AiFields
) -> str:
    title = normalize_name(ai.card_title or "")
    ai_id = ai.id_number or ""
    if qr is not None and qr_side == SIDE_FRONT:
        return CARD_CCCD_CHIP_2021
    if qr is not None and qr_side == SIDE_BACK:
        return CARD_CAN_CUOC_2024
    if mrz_found:
        if "CONG DAN" in title:
            return CARD_CCCD_CHIP_2021
        if "CAN CUOC" in title:
            return CARD_CAN_CUOC_2024
        return CARD_CCCD_CHIP_UNKNOWN
    if "CHUNG MINH" in title or len(ai_id) == 9:
        return CARD_CMND
    if len(ai_id) == 12 and "CAN CUOC CONG DAN" in title:
        return CARD_CCCD_BARCODE
    return CARD_UNKNOWN


def fuse(sig: DocumentSignals, cfg: EkycSettings) -> FusionResult:
    """Ma trận quyết định của ``/document``."""
    warnings = _Codes()
    reasons = _Codes()
    qr, qr_by, qr_side = _choose_qr(sig.qr, warnings)
    qr_found = sig.qr.server_found or sig.qr.client_text_present
    mrz = sig.mrz.result if sig.mrz.valid else None
    ai = sig.ai
    ai_id12 = ai.id_number if ai.id_number and len(ai.id_number) == 12 else None

    id_value, id_sources, id_conflict = _pick(
        qr.id_number if qr else None, mrz.id_number if mrz else None, ai_id12
    )
    dob_value, dob_sources, dob_conflict = _pick(
        qr.date_of_birth if qr else None,
        mrz.birth_date if mrz else None,
        ai.date_of_birth,
    )
    gender_value, gender_sources, gender_conflict = _pick(
        qr.gender if qr else None, mrz.gender if mrz else None, ai.gender
    )
    sources_disagree = id_conflict or dob_conflict or gender_conflict

    # Số định danh in ở mặt sau (MRZ, hoặc QR nếu QR nằm mặt sau — thẻ 2024).
    back_id = (
        mrz.id_number
        if mrz
        else (qr.id_number if qr and qr_side == SIDE_BACK else None)
    )
    linked = back_id is not None and (
        (
            qr is not None
            and qr_by == BY_SERVER
            and qr_side == SIDE_FRONT
            and qr.id_number == back_id
        )
        or sig.ocr_front_match is True
        or (ai.front_id is not None and ai.front_id == back_id)
    )
    if id_value is not None and sig.ocr_front_match is True and id_value == back_id:
        id_sources.append(SOURCE_OCR)

    # Họ tên: QR có dấu > AI (chỉ nhận nếu khớp tên MRZ) > MRZ không dấu.
    mrz_name = mrz.full_name if mrz else None
    mrz_truncated = bool(mrz and not mrz.lines[2].endswith("<"))
    if qr is not None and qr.full_name:
        name_field = _field(qr.full_name, [SOURCE_QR])
        if mrz_name and not names_match(qr.full_name, mrz_name, mrz_truncated):
            warnings.add(WARN_FULL_NAME_MISMATCH)
    elif ai.full_name and (
        mrz_name is None or names_match(ai.full_name, mrz_name, mrz_truncated)
    ):
        name_field = _field(ai.full_name, [SOURCE_AI])
    elif mrz_name:
        name_field = _field(mrz_name, [SOURCE_MRZ])
    else:
        name_field = _field(None, [])

    if qr is not None and qr.address:
        address_field = _field(qr.address, [SOURCE_QR])
    elif ai.address:
        address_field = _field(ai.address, [SOURCE_AI])
    else:
        address_field = _field(None, [])

    if qr is not None and qr.issue_date:
        issue_field = _field(format_date(qr.issue_date), [SOURCE_QR])
    elif ai.issue_date:
        issue_field = _field(format_date(ai.issue_date), [SOURCE_AI])
    else:
        issue_field = _field(None, [])

    if mrz is not None:
        expiry_value, no_expiry = mrz.expiry_date, mrz.no_expiry
        expiry_sources = [SOURCE_MRZ]
        if ai.expiry_date is not None and ai.expiry_date == expiry_value:
            expiry_sources.append(SOURCE_AI)
    elif ai.expiry_date is not None or ai.no_expiry:
        expiry_value, no_expiry, expiry_sources = (
            ai.expiry_date,
            ai.no_expiry,
            [SOURCE_AI],
        )
        # Hạn thẻ do AI đọc không có check digit: phải rơi đúng mốc tuổi đổi thẻ.
        if expiry_value is not None and not id_rules.expiry_milestone_ok(
            dob_value, expiry_value
        ):
            warnings.add(WARN_EXPIRY_MILESTONE_MISMATCH)
    else:
        expiry_value, no_expiry, expiry_sources = None, False, []
    expiry_field = {
        "value": format_date(expiry_value),
        "sources": expiry_sources if (expiry_value is not None or no_expiry) else [],
        "no_expiry": no_expiry,
    }

    optional = cfg.optional_fields
    fields = {
        "id_number": _field(id_value, id_sources),
        "full_name": name_field,
        "date_of_birth": _field(format_date(dob_value), dob_sources),
        "gender": _field(gender_value, gender_sources),
        "address": address_field,
        "place_of_origin": _field(ai.place_of_origin, [SOURCE_AI])
        if optional and ai.place_of_origin
        else _field(None, []),
        "birth_registration_place": _field(ai.birth_registration_place, [SOURCE_AI])
        if optional and ai.birth_registration_place
        else _field(None, []),
        "issue_date": issue_field,
        "expiry_date": expiry_field,
    }

    rules = id_rules.check_id(id_value, dob_value, gender_value)
    for warning in rules.warnings:
        warnings.add(warning)
    if mrz is not None and not mrz.id12_ok:
        warnings.add(WARN_MRZ_ID12_CHECK_FAILED)

    card_type = _card_type(qr, qr_side, sig.mrz.found, ai)
    checks = {
        "qr": {
            "found": qr_found,
            "by": qr_by,
            "side": qr_side,
            "valid": qr is not None,
        },
        "mrz": checks_dict(sig.mrz),
        "id_rules_ok": rules.ok,
        "sources_agree": not sources_disagree,
        "front_back_linked": linked,
    }

    # ── RETAKE ────────────────────────────────────────────────────────
    retakes: list[tuple[str, str, str]] = []
    if not sig.portrait.found:
        reason = (
            RETAKE_WRONG_SIDE if sig.portrait.wrong_side else RETAKE_PORTRAIT_UNUSABLE
        )
        retakes.append((SIDE_FRONT, reason, _KIND_PORTRAIT))
    qr_front_ok = qr is not None and qr_side == SIDE_FRONT
    if not sig.front_quality.ok and not qr_front_ok:
        retakes.append((SIDE_FRONT, sig.front_quality.issues[0], _KIND_OTHER))
    if not sig.back_quality.ok and mrz is None:
        retakes.append((SIDE_BACK, sig.back_quality.issues[0], _KIND_OTHER))
    if sig.mrz.found and not sig.mrz.valid:
        retakes.append((SIDE_BACK, RETAKE_MRZ_UNREADABLE, _KIND_OTHER))
    if sources_disagree:
        retakes.append((qr_side or SIDE_FRONT, RETAKE_SOURCES_DISAGREE, _KIND_OTHER))
        retakes.append((SIDE_BACK, RETAKE_SOURCES_DISAGREE, _KIND_OTHER))
    if id_value is None and card_type != CARD_CMND:
        # CMND không có số 12 chữ số: để finora-user từ chối (CARD_UNSUPPORTED) thay vì
        # bắt chụp lại mãi.
        retakes.append((SIDE_FRONT, RETAKE_NO_DATA, _KIND_NO_DATA))
        retakes.append((SIDE_BACK, RETAKE_NO_DATA, _KIND_NO_DATA))
    if sig.retake_count >= cfg.max_retakes:
        retakes = [r for r in retakes if r[2] in (_KIND_PORTRAIT, _KIND_NO_DATA)]
        if sources_disagree:
            reasons.add(REASON_SOURCES_DISAGREE_PERSISTENT)

    retake_list: list[dict] = []
    for side, reason, _ in retakes:
        item = {"side": side, "reason": reason}
        if item not in retake_list:
            retake_list.append(item)
    if retake_list:
        for item in retake_list:
            reasons.add(item["reason"])
        return FusionResult(
            DECISION_RETAKE,
            card_type,
            reasons.items,
            warnings.items,
            retake_list,
            fields,
            checks,
        )

    # ── AUTO_OK hay NEEDS_REVIEW ──────────────────────────────────────
    blockers: list[str] = []
    qr_mrz_agree = (
        qr is not None
        and mrz is not None
        and not sources_disagree
        and qr.gender is not None
        and mrz.gender is not None
    )
    if qr_mrz_agree:
        reasons.add(REASON_QR_MRZ_AGREE)
    elif sources_disagree:
        blockers.append(REASON_SOURCES_DISAGREE_PERSISTENT)
    elif qr is None and mrz is None:
        blockers.append(REASON_AI_ONLY)
    else:
        # Một nguồn tất định (hoặc hai nguồn nhưng thiếu giới tính ở một bên): chỉ
        # AUTO_OK khi bật cờ và có nguồn đọc độc lập (OCR/AI) khớp số + ngày sinh + giới tính.
        confirmed = (
            cfg.allow_single_deterministic
            and id_value is not None
            and (SOURCE_AI in id_sources or SOURCE_OCR in id_sources)
            and SOURCE_AI in dob_sources
            and SOURCE_AI in gender_sources
        )
        if confirmed:
            reasons.add(REASON_SINGLE_SOURCE_CONFIRMED)
        else:
            blockers.append(REASON_SINGLE_SOURCE)
            if qr is None:
                blockers.append(REASON_QR_MISSING)
            elif mrz is None:
                blockers.append(REASON_MRZ_MISSING)
            else:
                blockers.append(REASON_SOURCES_INCOMPLETE)

    if card_type == CARD_CCCD_BARCODE:
        blockers.append(REASON_CARD_BARCODE)
    elif card_type == CARD_CMND:
        blockers.append(REASON_CARD_CMND)
    elif card_type == CARD_UNKNOWN:
        blockers.append(REASON_CARD_UNKNOWN)

    if rules.ok:
        reasons.add(REASON_ID_RULES_OK)
    else:
        blockers.append(REASON_ID_RULES_FAILED)
    if warnings.items:
        blockers.append(REASON_SOFT_FLAGS)
    if linked:
        reasons.add(REASON_FRONT_BACK_LINKED)
    else:
        blockers.append(REASON_NOT_LINKED)
    if sig.front_quality.ok and sig.back_quality.ok:
        reasons.add(REASON_QUALITY_OK)
    else:
        blockers.append(REASON_QUALITY_LOW)
    reasons.add(REASON_PORTRAIT_OK)
    if SOURCE_GALLERY in (sig.front_source, sig.back_source):
        blockers.append(REASON_GALLERY_SOURCE)
    if no_expiry:
        blockers.append(REASON_NO_EXPIRY_UNVERIFIED)
    elif expiry_value is None:
        blockers.append(REASON_EXPIRY_UNREADABLE)

    for code in blockers:
        reasons.add(code)
    decision = DECISION_NEEDS_REVIEW if blockers else DECISION_AUTO_OK
    return FusionResult(
        decision, card_type, reasons.items, warnings.items, [], fields, checks
    )
