package com.finora.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.client.AiLivenessClient;
import com.finora.user.client.AiLivenessClient.AiLivenessException;
import com.finora.user.client.AiLivenessClient.LivenessMedia;
import com.finora.user.client.AiLivenessClient.LivenessResult;
import com.finora.user.client.AiLivenessClient.LivenessSpec;
import com.finora.user.client.AiLivenessClient.SpecStep;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.domain.LivenessStep;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.mapper.EkycResultMapper;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycChallengeStore.StoredChallenge;
import com.finora.user.service.EkycEvidenceService.BinaryFile;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import com.finora.user.support.EkycMessages;
import com.finora.user.support.Sha256;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Kiểm tra khuôn mặt (liveness + so khớp với ảnh chân dung trên thẻ) — EKYC-002 mục 3.4.
 * <p>
 * <b>Thử thách</b>: chỉ cấp khi có bản nháp hợp lệ và đồng ý còn hiệu lực; lịch tư thế ngẫu nhiên gắn
 * với lượt hiện tại, sống 90 giây, dùng một lần.
 * <p>
 * <b>Gửi video</b>, xử lý theo đúng thứ tự hợp đồng: rate limit → lấy và huỷ thử thách → bản nháp →
 * kiểm kích thước/kiểu → chống phát lại bằng SHA-256 → AI chấm → lưu video và khung bằng chứng → ghi
 * kết quả vào lượt và bản nháp → ánh xạ mã. Trượt 3 lần trong 24 giờ thì lượt và hồ sơ chuyển duyệt
 * tay kèm bản nháp mã hoá để nhân viên duyệt được. Không giữ transaction trong lúc gọi AI hay kho lưu.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycLivenessService {

    /** Giới hạn giống hệt finora-ai để file bị từ chối ở đây với mã rõ ràng, không đi vòng qua AI. */
    static final long MAX_VIDEO_BYTES = 10L * 1024 * 1024;
    static final long MAX_FRAME_BYTES = 1_500_000L;
    static final int MAX_FRAMES = EkycEvidenceService.MAX_STILL_FRAMES;

    static final String MODE_VIDEO = "VIDEO";
    static final String MODE_STILLS = "STILLS";
    static final String MEDIA_REPLAYED = "MEDIA_REPLAYED";

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };

    private final UserProfileRepository userProfileRepository;
    private final EkycAttemptRepository attemptRepository;
    private final EkycEvidenceRepository evidenceRepository;
    private final EkycConsentService consentService;
    private final EkycDraftStore draftStore;
    private final EkycChallengeStore challengeStore;
    private final EkycRateLimitService rateLimitService;
    private final EkycCounterStore counterStore;
    private final AiLivenessClient livenessClient;
    private final EkycEvidenceService evidenceService;
    private final EkycAttemptService attemptService;
    private final EkycResultFile resultFile;
    private final EkycSessionCleanup sessionCleanup;
    private final EkycClaimPublisher claimPublisher;
    private final LivenessChallengeGenerator challengeGenerator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // ── Thử thách ───────────────────────────────────────────────────

    public LivenessChallengeResponse createChallenge(UUID keycloakUserId) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        if (profile.getEkycStatus() == EkycStatus.MANUAL_REVIEW) {
            throw conflict(EkycResultCode.REVIEW_PENDING);
        }
        if (consentService.findActiveConsent(profile.getId()).isEmpty()) {
            throw conflict(EkycResultCode.CONSENT_REQUIRED);
        }
        EkycIdentityDraft draft = draftStore.find(keycloakUserId)
                .orElseThrow(() -> conflict(EkycResultCode.DRAFT_EXPIRED));
        if (!rateLimitService.tryAcquireChallengeSlot(keycloakUserId)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, EkycResultCode.RATE_LIMITED.name(),
                    EkycMessages.result(EkycResultCode.RATE_LIMITED));
        }
        List<LivenessStep> steps = challengeGenerator.generate();
        UUID sessionId = UUID.randomUUID();
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        challengeStore.save(keycloakUserId, new StoredChallenge(sessionId, steps, draft.attemptId(), issuedAt.toString()));
        log.info("Cấp thử thách liveness: userProfileId={}, attemptId={}", profile.getId(), draft.attemptId());
        return new LivenessChallengeResponse(
                sessionId,
                issuedAt.plus(EkycChallengeStore.CHALLENGE_TTL),
                LivenessChallengeGenerator.maxDurationSec(steps),
                steps.stream().map(step -> new LivenessChallengeResponse.Step(step.action().name(), step.holdMs())).toList(),
                LivenessChallengeResponse.VideoSettings.DEFAULT);
    }

    // ── Gửi video ───────────────────────────────────────────────────

    public EkycResultResponse submit(UUID keycloakUserId, LivenessUpload upload) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        EkycStatus status = profile.getEkycStatus();
        Instant now = clock.instant();

        // 1. Rate limit
        if (!rateLimitService.tryAcquireLivenessSlot(keycloakUserId)) {
            return EkycResultMapper.simple(status, EkycResultCode.RATE_LIMITED, EkycNextStep.LIVENESS);
        }
        // 2. Thử thách dùng một lần (GETDEL), đúng phiên và còn hạn
        Optional<StoredChallenge> challenge = challengeStore.consume(keycloakUserId);
        if (challenge.isEmpty() || !matchesSession(challenge.get(), upload.sessionId())
                || challenge.get().issuedAtInstant().plus(EkycChallengeStore.CHALLENGE_TTL).isBefore(now)) {
            return EkycResultMapper.simple(status, EkycResultCode.CHALLENGE_EXPIRED, EkycNextStep.LIVENESS);
        }
        // 3. Bản nháp của đúng lượt mà thử thách đã gắn
        EkycIdentityDraft draft = draftStore.find(keycloakUserId).orElse(null);
        if (draft == null) {
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }
        if (!draft.attemptId().equals(challenge.get().attemptId())) {
            return EkycResultMapper.simple(status, EkycResultCode.CHALLENGE_EXPIRED, EkycNextStep.LIVENESS);
        }
        EkycAttempt attempt = attemptRepository.findByPublicId(draft.attemptId())
                .filter(found -> found.isInProgress() && found.getUserProfileId().equals(profile.getId()))
                .orElse(null);
        if (attempt == null) {
            draftStore.remove(keycloakUserId);
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }
        // 4. Kích thước và kiểu file
        PreparedMedia media = prepareMedia(upload);
        // 5. Chống phát lại: cùng một file đã từng gửi trong 30 ngày
        if (!counterStore.registerMedia(media.sha256())) {
            log.warn("Video/ảnh liveness bị gửi lại: userProfileId={}, attemptId={}", profile.getId(), draft.attemptId());
            return recordFailure(keycloakUserId, profile, attempt, draft, List.of(MEDIA_REPLAYED), null,
                    draft.livenessThresholdsVersion(), media.captureMode(), draft.livenessRound(), List.of(), now);
        }
        // 6. AI chấm
        LivenessResult result;
        try {
            result = livenessClient.analyze(
                    new LivenessSpec(toSpecSteps(challenge.get().steps()), draft.portraitEmbeddingB64(),
                            media.captureMode(), media.frameTimesMs(), normalizePlatform(upload.platform())),
                    media.toClientMedia());
        } catch (AiLivenessException e) {
            if (e.isInvalidInput()) {
                log.info("AI không đọc được video liveness: userProfileId={}, status={}", profile.getId(), e.getStatus());
                return EkycResultMapper.liveness(status, EkycResultCode.RETAKE,
                        EkycMessages.livenessReason("VIDEO_UNREADABLE").orElse(EkycMessages.LIVENESS_RETAKE),
                        draft, EkycNextStep.LIVENESS, null, "RETAKE", null, List.of("VIDEO_UNREADABLE"), false);
            }
            rateLimitService.releaseLivenessSlot(keycloakUserId);
            log.warn("Không gọi được AI chấm liveness: userProfileId={}, status={}", profile.getId(), e.getStatus());
            return EkycResultMapper.simple(status, EkycResultCode.AI_UNAVAILABLE, EkycNextStep.LIVENESS);
        }
        // 7. Lưu video/ảnh và khung bằng chứng
        int round = draft.livenessRound() + 1;
        List<StoredEvidence> stored;
        try {
            stored = evidenceService.storeLiveness(profile.getId(), draft.attemptId(), round,
                    media.video(), media.stills(), result.evidenceFrames());
        } catch (EkycStorageException e) {
            log.warn("Không lưu được video liveness: userProfileId={}, attemptId={}, cause={}",
                    profile.getId(), draft.attemptId(), e.getMessage());
            return EkycResultMapper.simple(status, EkycResultCode.STORAGE_UNAVAILABLE, EkycNextStep.LIVENESS);
        }
        // 8–9. Ghi kết quả và ánh xạ mã
        List<String> reasons = reasonsWithZone(result);
        Double score = result.faceMatch() == null ? null : result.faceMatch().score();
        String decision = result.decision().trim().toUpperCase(Locale.ROOT);
        log.info("Kết quả liveness: userProfileId={}, attemptId={}, decision={}, round={}",
                profile.getId(), draft.attemptId(), decision, round);
        return switch (decision) {
            case "PASS" -> recordConclusion(keycloakUserId, status, attempt, draft, EkycLivenessResult.PASS,
                    reasons, score, result.thresholdsVersion(), media.captureMode(), round, stored, now);
            case "REVIEW" -> recordConclusion(keycloakUserId, status, attempt, draft, EkycLivenessResult.REVIEW,
                    reasons, score, result.thresholdsVersion(), media.captureMode(), round, stored, now);
            case "FAIL" -> recordFailure(keycloakUserId, profile, attempt, draft, reasons, score,
                    result.thresholdsVersion(), media.captureMode(), round, stored, now);
            // RETAKE hoặc giá trị lạ: không có kết luận về người dùng, không tính là một lần trượt.
            default -> recordRetake(keycloakUserId, status, attempt, draft, reasons, score, round, stored, now);
        };
    }

    // ── Ghi kết quả ─────────────────────────────────────────────────

    private EkycResultResponse recordConclusion(UUID keycloakUserId, EkycStatus status, EkycAttempt attempt,
                                                EkycIdentityDraft draft, EkycLivenessResult conclusion,
                                                List<String> reasons, Double score, String thresholdsVersion,
                                                String captureMode, int round, List<StoredEvidence> stored,
                                                Instant now) {
        EkycIdentityDraft updated = draft.withLiveness(conclusion, score, reasons, thresholdsVersion, captureMode, round);
        if (!persistLiveness(attempt, updated, conclusion, stored, now)) {
            draftStore.remove(keycloakUserId);
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }
        draftStore.save(keycloakUserId, updated);
        EkycResultCode code = conclusion == EkycLivenessResult.PASS
                ? EkycResultCode.LIVENESS_PASSED
                : EkycResultCode.LIVENESS_REVIEW;
        return EkycResultMapper.liveness(status, code, EkycMessages.result(code), updated, EkycNextStep.CONFIRM,
                null, conclusion == EkycLivenessResult.PASS ? "PASSED" : "REVIEW", score, reasons, true);
    }

    private EkycResultResponse recordRetake(UUID keycloakUserId, EkycStatus status, EkycAttempt attempt,
                                            EkycIdentityDraft draft, List<String> reasons, Double score, int round,
                                            List<StoredEvidence> stored, Instant now) {
        EkycIdentityDraft updated = draft.withLivenessRound(round);
        if (!persistLiveness(attempt, updated, null, stored, now)) {
            draftStore.remove(keycloakUserId);
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }
        draftStore.save(keycloakUserId, updated);
        String message = EkycResultMapper.toReasons(reasons).stream()
                .findFirst()
                .map(EkycResultResponse.LivenessReason::message)
                .orElse(EkycMessages.LIVENESS_RETAKE);
        return EkycResultMapper.liveness(status, EkycResultCode.RETAKE, message, updated, EkycNextStep.LIVENESS,
                null, "RETAKE", score, reasons, false);
    }

    /**
     * Một lần trượt (AI kết luận FAIL hoặc file bị gửi lại). Lần thứ 3 trong 24 giờ: lượt và hồ sơ chuyển
     * duyệt tay — bản nháp (không embedding) mã hoá vào lượt, bằng chứng sang {@code final}, có
     * {@code result.json} nếu kho ghi được — để nhân viên xem và duyệt như hồ sơ gửi từ bước xác nhận.
     */
    private EkycResultResponse recordFailure(UUID keycloakUserId, UserProfile profile, EkycAttempt attempt,
                                             EkycIdentityDraft draft, List<String> reasons, Double score,
                                             String thresholdsVersion, String captureMode, int round,
                                             List<StoredEvidence> stored, Instant now) {
        EkycStatus status = profile.getEkycStatus();
        EkycIdentityDraft updated = draft.withLiveness(EkycLivenessResult.FAIL, score, reasons, thresholdsVersion,
                captureMode, round);
        if (!persistLiveness(attempt, updated, EkycLivenessResult.FAIL, stored, now)) {
            draftStore.remove(keycloakUserId);
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }
        int failures = counterStore.recordLivenessFailure(keycloakUserId);
        if (failures >= EkycCounterStore.MAX_LIVENESS_FAILURES) {
            return escalateToReview(keycloakUserId, profile, attempt, updated, reasons, score, now);
        }
        draftStore.save(keycloakUserId, updated);
        EkycResultCode code = reasons.contains("FACE_MISMATCH")
                ? EkycResultCode.FACE_MISMATCH
                : EkycResultCode.LIVENESS_FAILED;
        String message = reasons.contains(MEDIA_REPLAYED)
                ? EkycMessages.livenessReason(MEDIA_REPLAYED).orElse(EkycMessages.result(code))
                : EkycMessages.result(code);
        return EkycResultMapper.liveness(status, code, message, updated, EkycNextStep.LIVENESS,
                EkycCounterStore.MAX_LIVENESS_FAILURES - failures, "FAILED", score, reasons, false);
    }

    private EkycResultResponse escalateToReview(UUID keycloakUserId, UserProfile profile, EkycAttempt attempt,
                                                EkycIdentityDraft draft, List<String> reasons, Double score,
                                                Instant now) {
        List<EkycEvidence> liveEvidence = evidenceRepository.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                .filter(evidence -> !evidence.isDeleted())
                .toList();
        evidenceService.markFinal(liveEvidence.stream().map(EkycEvidence::getObjectKey).toList());
        StoredEvidence result = null;
        try {
            result = evidenceService.storeResult(profile.getId(), draft.attemptId(),
                    resultFile.build(attempt, draft, "MANUAL_REVIEW", liveEvidence, now));
        } catch (EkycStorageException e) {
            // Video và ảnh thẻ đã nằm trên kho; thiếu bản tóm tắt không được chặn việc chuyển duyệt tay.
            log.warn("Không ghi được result.json khi chuyển duyệt tay: attemptId={}", draft.attemptId());
        }
        StoredEvidence resultFileEvidence = result;
        Boolean escalated = transactionTemplate.execute(tx -> {
            UserProfile fresh = userProfileRepository.findById(profile.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "id", profile.getId()));
            EkycAttempt current = attemptRepository.findById(attempt.getId()).orElse(null);
            if (current == null || !current.isInProgress()
                    || fresh.getEkycStatus() == EkycStatus.VERIFIED || fresh.getEkycStatus() == EkycStatus.MANUAL_REVIEW) {
                return false;
            }
            attemptService.submitForReview(fresh, current, draft, resultFileEvidence, now);
            return true;
        });
        sessionCleanup.clearAll(keycloakUserId);
        if (!Boolean.TRUE.equals(escalated)) {
            return EkycResultMapper.simple(profile.getEkycStatus(), EkycResultCode.DRAFT_EXPIRED);
        }
        claimPublisher.publish(keycloakUserId, EkycStatus.MANUAL_REVIEW);
        log.info("Trượt liveness {} lần, chuyển duyệt tay: userProfileId={}, attemptId={}",
                EkycCounterStore.MAX_LIVENESS_FAILURES, profile.getId(), draft.attemptId());
        return EkycResultMapper.liveness(EkycStatus.MANUAL_REVIEW, EkycResultCode.REVIEW_PENDING,
                EkycMessages.result(EkycResultCode.REVIEW_PENDING), draft, EkycNextStep.NONE, 0, "FAILED",
                score, reasons, false);
    }

    /** Ghi bằng chứng mới và (nếu có) kết luận vào lượt; {@code false} nếu lượt đã đóng trong lúc chờ AI. */
    private boolean persistLiveness(EkycAttempt attempt, EkycIdentityDraft updated, EkycLivenessResult conclusion,
                                    List<StoredEvidence> stored, Instant now) {
        Boolean persisted = transactionTemplate.execute(tx -> {
            EkycAttempt current = attemptRepository.findById(attempt.getId()).orElse(null);
            if (current == null || !current.isInProgress()) {
                return false;
            }
            attemptService.recordLiveness(current, conclusion, updated.livenessReasons(), updated.faceMatchScore(),
                    updated.combinedThresholdsVersion(), stored, now);
            return true;
        });
        return Boolean.TRUE.equals(persisted);
    }

    // ── Kiểm tra file ───────────────────────────────────────────────

    /** Kiểm chế độ quay, kích thước và kiểu file thật (magic bytes), rồi băm nội dung để chống phát lại. */
    PreparedMedia prepareMedia(LivenessUpload upload) {
        String mode = upload.captureMode() == null || upload.captureMode().isBlank()
                ? MODE_VIDEO
                : upload.captureMode().trim().toUpperCase(Locale.ROOT);
        if (MODE_VIDEO.equals(mode)) {
            UploadedFile video = upload.video();
            if (video == null || video.bytes() == null || video.bytes().length == 0) {
                throw invalidMedia("Thiếu video liveness.");
            }
            if (video.bytes().length > MAX_VIDEO_BYTES) {
                throw tooLarge();
            }
            String container = detectContainer(video.bytes());
            if (container == null) {
                throw invalidMedia("Video phải là MP4 hoặc MOV.");
            }
            BinaryFile file = "mov".equals(container)
                    ? new BinaryFile(video.bytes(), "video/quicktime", "mov")
                    : new BinaryFile(video.bytes(), "video/mp4", "mp4");
            return new PreparedMedia(MODE_VIDEO, file, List.of(), List.of(), Sha256.hex(video.bytes()));
        }
        if (MODE_STILLS.equals(mode)) {
            List<UploadedFile> frames = upload.frames() == null ? List.of() : upload.frames();
            if (frames.isEmpty() || frames.size() > MAX_FRAMES) {
                throw invalidMedia("Cần từ 1 đến " + MAX_FRAMES + " ảnh.");
            }
            List<byte[]> stills = new ArrayList<>();
            for (UploadedFile frame : frames) {
                if (frame == null || frame.bytes() == null || frame.bytes().length == 0) {
                    throw invalidMedia("Có ảnh rỗng.");
                }
                if (frame.bytes().length > MAX_FRAME_BYTES) {
                    throw tooLarge();
                }
                if (!isJpeg(frame.bytes())) {
                    throw invalidMedia("Ảnh phải là JPEG.");
                }
                stills.add(frame.bytes());
            }
            List<Long> frameTimes = parseFrameTimes(upload.frameTimesMs());
            if (frameTimes.size() != stills.size()) {
                throw invalidMedia("Số mốc thời gian không khớp số ảnh.");
            }
            return new PreparedMedia(MODE_STILLS, null, stills, frameTimes, Sha256.hex(stills));
        }
        throw invalidMedia("Chế độ quay chỉ nhận VIDEO hoặc STILLS.");
    }

    private List<Long> parseFrameTimes(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidMedia("Thiếu mốc thời gian của ảnh.");
        }
        List<Long> values;
        try {
            values = objectMapper.readValue(raw, LONG_LIST);
        } catch (JsonProcessingException e) {
            throw invalidMedia("Mốc thời gian không hợp lệ.");
        }
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value < 0)) {
            throw invalidMedia("Mốc thời gian không hợp lệ.");
        }
        // AI ghép ảnh với lịch thử thách theo thời gian nên mốc phải tăng dần nghiêm ngặt.
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i) <= values.get(i - 1)) {
                throw invalidMedia("Mốc thời gian phải tăng dần.");
            }
        }
        return List.copyOf(values);
    }

    /**
     * Video ISO-BMFF có hộp {@code ftyp} ở byte 4–8; nhãn {@code qt  } là QuickTime (iOS), còn lại là MP4.
     * Một số máy cũ ghi MOV bắt đầu bằng {@code moov}/{@code mdat}/{@code wide}/{@code free}.
     */
    static String detectContainer(byte[] bytes) {
        if (bytes.length < 12) {
            return null;
        }
        String box = new String(bytes, 4, 4, StandardCharsets.US_ASCII);
        if ("ftyp".equals(box)) {
            String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
            return "qt  ".equals(brand) ? "mov" : "mp4";
        }
        if ("moov".equals(box) || "mdat".equals(box) || "wide".equals(box) || "free".equals(box)) {
            return "mov";
        }
        return null;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    // ── Helper ──────────────────────────────────────────────────────

    /**
     * Lưu mã vùng so mặt cùng các mã lý do để trang duyệt suy ra HIGH/GRAY/LOW kể cả khi AI không tự đưa
     * mã đó vào {@code reasons}.
     */
    private static List<String> reasonsWithZone(LivenessResult result) {
        List<String> reasons = new ArrayList<>(result.reasons() == null ? List.of() : result.reasons().stream()
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList());
        String zoneCode = EkycAttemptService.zoneReasonCode(result.faceMatch() == null ? null : result.faceMatch().zone());
        if (zoneCode != null && EkycAttemptService.faceMatchZone(reasons) == null) {
            reasons.add(zoneCode);
        }
        return List.copyOf(reasons);
    }

    /** Chỉ chuyển ba giá trị hợp lệ cho AI; giá trị lạ coi như không biết nền tảng. */
    static String normalizePlatform(String platform) {
        if (platform == null) {
            return null;
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "ios", "android", "web" -> normalized;
            default -> null;
        };
    }

    private static boolean matchesSession(StoredChallenge challenge, String sessionId) {
        return sessionId != null && challenge.sessionId() != null
                && challenge.sessionId().toString().equalsIgnoreCase(sessionId.trim());
    }

    private static List<SpecStep> toSpecSteps(List<LivenessStep> steps) {
        return steps.stream().map(step -> new SpecStep(step.action().name(), step.holdMs())).toList();
    }

    private static BusinessException conflict(EkycResultCode code) {
        return new BusinessException(HttpStatus.CONFLICT, code.name(), EkycMessages.result(code));
    }

    private static BusinessException invalidMedia(String detail) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA",
                "Video hoặc ảnh gửi lên không hợp lệ. " + detail);
    }

    private static BusinessException tooLarge() {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Tệp gửi lên vượt quá dung lượng cho phép.");
    }

    private UserProfile findProfileOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }

    // ── Kiểu dữ liệu ────────────────────────────────────────────────

    /**
     * Dữ liệu multipart đã đọc ở controller (không phụ thuộc kiểu HTTP).
     *
     * @param frameTimesMs mảng JSON mốc thời gian (ms) của từng ảnh ở chế độ STILLS
     */
    public record LivenessUpload(String sessionId, String captureMode, String platform, UploadedFile video,
                                 List<UploadedFile> frames, String frameTimesMs) {
    }

    public record UploadedFile(String originalFilename, String contentType, byte[] bytes) {
    }

    /** File đã kiểm hợp lệ, sẵn sàng gửi AI và lưu kho. */
    record PreparedMedia(String captureMode, BinaryFile video, List<byte[]> stills, List<Long> frameTimesMs,
                         String sha256) {

        LivenessMedia toClientMedia() {
            return video == null
                    ? new LivenessMedia(null, null, null, stills)
                    : new LivenessMedia(video.bytes(), video.contentType(), "liveness." + video.extension(), List.of());
        }
    }
}
