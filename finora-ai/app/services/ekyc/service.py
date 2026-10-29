"""Điều phối eKYC: đọc CCCD tất định (``/document``) và liveness + so mặt (``/liveness``).

finora-ai chỉ trả **bằng chứng kỹ thuật** (nguồn từng trường, check digit, điểm số).
Quyết định nghiệp vụ (tuổi, hạn thẻ, CMND, trùng số, VERIFIED/MANUAL_REVIEW) thuộc
``finora-user``. Service không lưu ảnh, không log PII — chỉ log mã quyết định, cờ,
thời gian xử lý.

Thứ tự đọc thẻ (hợp đồng EKYC-002 mục 2.1):
1. nắn thẻ + cổng chất lượng → 2. chân dung (YuNet/SFace) → 3. QR (client + server tự
giải) → 4. MRZ (Tesseract) → 5. gắn hai mặt bằng số in mặt trước (Tesseract) →
6. Gemini chỉ điền chỗ trống (song song MRZ + trường mặt trước nếu cần) → 7. hợp nhất.
"""

from __future__ import annotations

import logging
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace

from numpy.typing import NDArray

from app.ml.ekyc import (
    card_warp,
    face,
    front_id_ocr,
    fusion,
    gemini_extractor,
    image_io,
    liveness_video,
    mrz,
    qr_reader,
    quality,
)
from app.ml.ekyc.gemini_extractor import FrontRead, GeminiCardReader
from app.ml.ekyc.thresholds import (
    DocThresholds,
    EkycSettings,
    LivenessThresholds,
    doc_thresholds,
    liveness_thresholds,
    settings,
)
from app.schemas.ekyc import DocumentRequest, LivenessSpec

logger = logging.getLogger(__name__)

#: Phần trái mặt trước bị che trước khi gửi AI (chân dung + quốc huy).
GEMINI_MASK_RATIO = 0.38
_GEMINI_MAX_WIDTH = 1400
_UNSET = object()


@dataclass
class _DeterministicReads:
    """Kết quả các nguồn tất định trước khi hỏi AI."""

    client_payload: qr_reader.QrPayload | None
    server_read: qr_reader.QrRead | None
    server_side: str | None
    mrz_read: mrz.MrzRead

    @property
    def qr_payload(self) -> qr_reader.QrPayload | None:
        if self.server_read is not None and self.server_read.payload is not None:
            return self.server_read.payload
        return self.client_payload

    def back_id(self, client_side: str | None) -> str | None:
        """Số định danh đọc được từ mặt sau (MRZ, hoặc QR nằm mặt sau)."""
        if self.mrz_read.valid:
            return self.mrz_read.result.id_number
        if self.server_read is not None and self.server_read.payload is not None:
            return (
                self.server_read.payload.id_number
                if self.server_side == qr_reader.SIDE_BACK
                else None
            )
        if self.client_payload is not None and client_side == qr_reader.SIDE_BACK:
            return self.client_payload.id_number
        return None

    def linked_by_front_qr(self, back_id: str | None) -> bool:
        return (
            back_id is not None
            and self.server_read is not None
            and self.server_read.payload is not None
            and self.server_side == qr_reader.SIDE_FRONT
            and self.server_read.payload.id_number == back_id
        )


def _masked_front_jpeg(engine: face.FaceEngine, front_card: NDArray) -> bytes:
    """Mặt trước đã che 38% trái + tô đen mọi khuôn mặt dò được, sẵn sàng gửi AI."""
    masked = front_card.copy()
    masked[:, : int(masked.shape[1] * GEMINI_MASK_RATIO)] = 0
    masked = face.mask_faces(engine, masked)
    return image_io.encode_jpeg(masked, quality=90, width=_GEMINI_MAX_WIDTH)


def _ai_fields(
    front: FrontRead | None, front_id: str | None, called: bool
) -> fusion.AiFields:
    if front is None:
        return fusion.AiFields(called=called, front_id=front_id)
    return fusion.AiFields(
        called=called,
        card_title=front.card_title,
        id_number=front.id_number,
        full_name=front.full_name,
        date_of_birth=front.date_of_birth,
        gender=front.gender,
        address=front.address,
        place_of_origin=front.place_of_origin,
        birth_registration_place=front.birth_registration_place,
        issue_date=front.issue_date,
        expiry_date=front.expiry_date,
        no_expiry=front.no_expiry,
        front_id=front_id or front.id_number,
    )


class EkycService:
    """Dịch vụ eKYC; các phụ thuộc inject được để test (Gemini giả, ngưỡng riêng)."""

    def __init__(
        self,
        gemini: GeminiCardReader | None | object = _UNSET,
        cfg: EkycSettings | None = None,
        doc_th: DocThresholds | None = None,
        live_th: LivenessThresholds | None = None,
    ):
        self._cfg = cfg or settings()
        self._doc_th = doc_th or doc_thresholds()
        self._live_th = live_th or liveness_thresholds()
        self._gemini = (
            gemini_extractor.build_from_env(self._cfg) if gemini is _UNSET else gemini
        )

    # ── /document ─────────────────────────────────────────────────────

    def analyze_document(
        self, request: DocumentRequest, front_bytes: bytes, back_bytes: bytes
    ) -> dict:
        """Đọc hai mặt thẻ → bản nháp trường + nguồn + quyết định."""
        started = time.perf_counter()
        th, cfg = self._doc_th, self._cfg
        engine = face.get_engine()
        front_image = image_io.decode_image_bytes(front_bytes)
        back_image = image_io.decode_image_bytes(back_bytes)

        front_warp = card_warp.warp_card(
            front_image, th.card_min_area_ratio, th.card_warp_width
        )
        back_warp = card_warp.warp_card(
            back_image, th.card_min_area_ratio, th.card_warp_width
        )
        front_quality = quality.measure_quality(
            front_warp.image, front_warp.card_found, th
        )
        back_quality = quality.measure_quality(
            back_warp.image, back_warp.card_found, th
        )

        front_card, portrait = self._portrait(engine, front_warp.image)
        wrong_side = False
        if not portrait.found and portrait.faces == 0:
            wrong_side = face.any_face(
                engine, back_warp.image, th.portrait_min_score
            ) or face.any_face(
                engine, card_warp.rotate_180(back_warp.image), th.portrait_min_score
            )

        reads = self._deterministic_reads(
            request, front_image, front_warp, back_image, back_warp
        )
        back_id = reads.back_id(request.qr_side)
        ocr_match = None
        if back_id is not None and not reads.linked_by_front_qr(back_id):
            ocr_match = front_id_ocr.front_shows_id(
                front_card, back_id, th.front_id_max_mismatch, cfg.tesseract_timeout_s
            )

        reads, ai, ocr_match, ai_used = self._ask_ai(
            engine, request, reads, front_card, back_warp.image, ocr_match
        )

        signals = fusion.DocumentSignals(
            front_quality=front_quality,
            back_quality=back_quality,
            portrait=fusion.PortraitSignal(found=portrait.found, wrong_side=wrong_side),
            qr=fusion.QrEvidence(
                client_text_present=bool(request.qr_text and request.qr_text.strip()),
                client_payload=reads.client_payload,
                client_side=request.qr_side,
                server_found=reads.server_read is not None,
                server_payload=reads.server_read.payload if reads.server_read else None,
                server_side=reads.server_side,
            ),
            mrz=reads.mrz_read,
            ocr_front_match=ocr_match,
            ai=ai,
            front_source=request.front_source,
            back_source=request.back_source,
            retake_count=request.retake_count,
        )
        result = fusion.fuse(signals, cfg)
        portrait_block: dict = {
            "found": portrait.found,
            "ied_px": None if portrait.ied_px is None else round(portrait.ied_px, 1),
            "sharpness": None
            if portrait.sharpness is None
            else round(portrait.sharpness, 1),
        }
        if portrait.found and portrait.embedding is not None:
            portrait_block["embedding_b64"] = face.encode_embedding(portrait.embedding)

        logger.info(
            "eKYC document: decision=%s card_type=%s qr=%s/%s/%s mrz=%s/%s linked=%s "
            "portrait=%s ai_used=%s retake=%s retake_count=%d ms=%d",
            result.decision,
            result.card_type,
            result.checks["qr"]["by"],
            result.checks["qr"]["side"],
            result.checks["qr"]["valid"],
            result.checks["mrz"]["engine"],
            result.checks["mrz"]["valid"],
            result.checks["front_back_linked"],
            portrait.found,
            ai_used,
            [f"{r['side']}:{r['reason']}" for r in result.retake],
            request.retake_count,
            int((time.perf_counter() - started) * 1000),
        )
        return {
            "decision": result.decision,
            "card_type": result.card_type,
            "reasons": result.reasons,
            "warnings": result.warnings,
            "retake": result.retake,
            "fields": result.fields,
            "checks": result.checks,
            "quality": {
                "front": front_quality.as_dict(),
                "back": back_quality.as_dict(),
            },
            "portrait": portrait_block,
            "ai_used": ai_used,
            "thresholds_version": th.version,
        }

    def _portrait(
        self, engine: face.FaceEngine, front_card: NDArray
    ) -> tuple[NDArray, face.PortraitResult]:
        """Chân dung ở chiều thẻ hiện tại; không thấy mặt nào thì thử xoay 180°."""
        portrait = face.extract_portrait(engine, front_card, self._doc_th)
        if portrait.found or portrait.faces > 0:
            return front_card, portrait
        rotated = card_warp.rotate_180(front_card)
        alternative = face.extract_portrait(engine, rotated, self._doc_th)
        if alternative.found or alternative.faces > 0:
            return rotated, alternative
        return front_card, portrait

    def _deterministic_reads(
        self,
        request: DocumentRequest,
        front_image: NDArray,
        front_warp: card_warp.CardWarp,
        back_image: NDArray,
        back_warp: card_warp.CardWarp,
    ) -> _DeterministicReads:
        client_payload = (
            qr_reader.parse_qr_text(request.qr_text) if request.qr_text else None
        )
        sides = [
            (qr_reader.SIDE_FRONT, [front_image, front_warp.image]),
            (qr_reader.SIDE_BACK, [back_image, back_warp.image]),
        ]
        if request.qr_side == qr_reader.SIDE_BACK:
            sides.reverse()
        server_read: qr_reader.QrRead | None = None
        server_side: str | None = None
        for side, images in sides:
            read = qr_reader.decode_qr(images)
            if read is None:
                continue
            if read.payload is not None:
                server_read, server_side = read, side
                break
            if server_read is None:
                server_read, server_side = read, side

        mrz_read = mrz.read_with_tesseract(
            back_warp.image, self._cfg.tesseract_timeout_s
        )
        if mrz_read is None:
            mrz_read = mrz.MrzRead(found=False, result=None, engine=None)
        return _DeterministicReads(client_payload, server_read, server_side, mrz_read)

    def _ask_ai(
        self,
        engine: face.FaceEngine,
        request: DocumentRequest,
        reads: _DeterministicReads,
        front_card: NDArray,
        back_card: NDArray,
        ocr_match: bool | None,
    ) -> tuple[_DeterministicReads, fusion.AiFields, bool | None, bool]:
        """Gemini chỉ được hỏi phần còn thiếu; lỗi AI không làm hỏng request."""
        gemini = self._gemini
        if gemini is None:
            return reads, fusion.AiFields(), ocr_match, False

        cfg = self._cfg
        qr = reads.qr_payload
        need_mrz = not reads.mrz_read.valid
        need_fields = (
            qr is None
            or qr.full_name is None
            or qr.address is None
            or cfg.optional_fields
        )
        masked_cache: list[bytes] = []

        def masked_front() -> bytes:
            if not masked_cache:
                masked_cache.append(_masked_front_jpeg(engine, front_card))
            return masked_cache[0]

        front: FrontRead | None = None
        mrz_lines: list[str] | None = None
        called = False
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="ekyc-ai") as pool:
            mrz_future = None
            if need_mrz:
                band = image_io.encode_jpeg(
                    mrz.mrz_band(back_card), quality=90, width=1600
                )
                mrz_future = pool.submit(gemini.read_mrz_lines, band)
                called = True
            fields_future = None
            if need_fields:
                fields_future = pool.submit(
                    gemini.read_front_fields, masked_front(), cfg.optional_fields
                )
                called = True
            if mrz_future is not None:
                mrz_lines = mrz_future.result()
            if fields_future is not None:
                front = fields_future.result()

        if mrz_lines is not None:
            ai_mrz = mrz.best_of(
                [(mrz_lines[0], mrz_lines[1], mrz_lines[2] or None)], mrz.ENGINE_GEMINI
            )
            # Chỉ nhận MRZ của AI khi đủ check digit; MRZ hỏng của AI chỉ thay chỗ "không thấy".
            if ai_mrz.valid or (ai_mrz.found and not reads.mrz_read.found):
                reads = _DeterministicReads(
                    reads.client_payload, reads.server_read, reads.server_side, ai_mrz
                )

        back_id = reads.back_id(request.qr_side)
        front_id = front.id_number if front is not None else None
        linked = (
            reads.linked_by_front_qr(back_id)
            or ocr_match is True
            or (back_id is not None and front_id == back_id)
        )
        if back_id is not None and not linked:
            if ocr_match is None:
                ocr_match = front_id_ocr.front_shows_id(
                    front_card,
                    back_id,
                    self._doc_th.front_id_max_mismatch,
                    cfg.tesseract_timeout_s,
                )
            if ocr_match is not True and front is None:
                front_id = gemini.read_front_id(masked_front())
                called = True

        # Cờ "một nguồn tất định": cần AI đọc độc lập số/ngày sinh/giới tính.
        single_source = (qr is not None) != reads.mrz_read.valid
        if cfg.allow_single_deterministic and single_source and front is None:
            front = gemini.read_front_fields(masked_front(), cfg.optional_fields)
            called = True
        return reads, _ai_fields(front, front_id, called), ocr_match, called

    # ── /liveness ─────────────────────────────────────────────────────

    def analyze_liveness(
        self,
        spec: LivenessSpec,
        portrait_embedding: NDArray,
        video: bytes | None,
        frames: list[bytes] | None,
    ) -> dict:
        """Chấm một lượt quay liveness theo thử thách ``spec.steps``."""
        started = time.perf_counter()
        engine = face.get_engine()
        steps = [liveness_video.Step(s.action, s.hold_ms) for s in spec.steps]
        # Chiều quay trái/phải theo nền tảng quay video (iOS/Android có thể lật gương khác).
        th = replace(
            self._live_th, yaw_invert=self._live_th.yaw_invert_for(spec.platform)
        )
        if spec.capture_mode == liveness_video.MODE_STILLS:
            images = [image_io.decode_image_bytes(frame) for frame in frames or []]
            result = liveness_video.analyze_stills(
                images,
                [float(t) for t in spec.frame_times_ms or []],
                steps,
                portrait_embedding,
                engine,
                th,
            )
        else:
            result = liveness_video.analyze_video(
                video or b"", steps, portrait_embedding, engine, th
            )
        logger.info(
            "eKYC liveness: mode=%s platform=%s decision=%s reasons=%s zone=%s sampled=%d "
            "ms=%d",
            spec.capture_mode,
            spec.platform,
            result["decision"],
            result["reasons"],
            result["face_match"]["zone"],
            result["faces"]["sampled_frames"],
            int((time.perf_counter() - started) * 1000),
        )
        return result
