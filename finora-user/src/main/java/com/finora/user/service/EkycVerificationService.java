package com.finora.user.service;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.client.AiEkycClient;
import com.finora.user.client.AiEkycClient.DocumentFields;
import com.finora.user.client.AiEkycClient.DocumentResult;
import com.finora.user.client.AiEkycClient.FieldReading;
import com.finora.user.config.AiServiceProperties;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycConsent;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycDraftField;
import com.finora.user.domain.EkycDraftFieldName;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.EkycResultResponse.RetakeItem;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.mapper.EkycResultMapper;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycAttemptService.StartedAttempt;
import com.finora.user.service.EkycEvidenceService.BinaryFile;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import com.finora.user.support.EkycMessages;
import com.finora.user.util.CccdMatcher;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bước quét thẻ và bước xác nhận của eKYC (EKYC-002 mục 3.2, 3.5).
 * <p>
 * <b>Quét</b> ({@link #verify}): kiểm điều kiện rẻ trước (đã xác minh, đang chờ duyệt, đồng ý, kho lưu,
 * rate limit) rồi mới gọi AI đọc hai mặt thẻ. AI trả bằng chứng kỹ thuật; luật nghiệp vụ (CMND, hết hạn
 * theo ngày Việt Nam, dưới 18 tuổi, trùng số) do service này áp. Đạt thì mở lượt mới, đưa ảnh lên kho
 * (tag {@code pending}) và cất bản nháp mã hoá ở Redis — hồ sơ chưa được ghi.
 * <p>
 * <b>Xác nhận</b> ({@link #confirm}): cần liveness PASS/REVIEW. Giấy tờ {@code AUTO_OK} + liveness
 * {@code PASS} → {@code VERIFIED}; còn lại → {@code MANUAL_REVIEW}. Bằng chứng được gắn tag {@code final}
 * và {@code result.json} được ghi <b>trước</b> transaction: không bao giờ chuyển VERIFIED khi chưa lưu đủ
 * bằng chứng. Redis và claim Keycloak chỉ được đụng tới sau khi transaction đã commit.
 * <p>
 * Không có hàm nào giữ transaction trong lúc gọi AI hay kho lưu trữ; database chỉ được ghi trong các
 * khối {@link TransactionTemplate} ngắn giữa các lời gọi mạng.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycVerificationService {

    /** Ngày hết hạn và tuổi tính theo lịch Việt Nam, không theo UTC của server. */
    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    static final String NO_EXPIRY_TEXT = "Không thời hạn";

    private static final int ADULT_AGE = 18;
    private static final Pattern ID_NUMBER_12 = Pattern.compile("\\d{12}");
    private static final Pattern ID_NUMBER_CMND = Pattern.compile("\\d{9}");
    private static final Pattern DATA_URI_PREFIX = Pattern.compile("^data:[^,]{0,100};base64,", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final UserProfileRepository userProfileRepository;
    private final EkycAttemptRepository attemptRepository;
    private final EkycEvidenceRepository evidenceRepository;
    private final AiEkycClient aiEkycClient;
    private final AiServiceProperties aiProperties;
    private final EkycRateLimitService rateLimitService;
    private final EkycCounterStore counterStore;
    private final EkycDraftStore draftStore;
    private final EkycConsentService consentService;
    private final EkycEvidenceService evidenceService;
    private final EkycAttemptService attemptService;
    private final EkycIdentityService identityService;
    private final EkycResultFile resultFile;
    private final EkycSessionCleanup sessionCleanup;
    private final EkycClaimPublisher claimPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    // ── Bước quét thẻ ───────────────────────────────────────────────

    public EkycResultResponse verify(UUID keycloakUserId, EkycVerifyRequest request) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        EkycStatus status = profile.getEkycStatus();

        // Kiểm tra rẻ đặt trước để không tiêu suất rate limit vào lần gọi chắc chắn không cần AI.
        if (status == EkycStatus.VERIFIED) {
            return EkycResultMapper.alreadyVerified();
        }
        if (status == EkycStatus.MANUAL_REVIEW) {
            return EkycResultMapper.reviewPending();
        }
        Optional<EkycConsent> consent = consentService.findActiveConsent(profile.getId());
        if (consent.isEmpty()) {
            return EkycResultMapper.simple(status, EkycResultCode.CONSENT_REQUIRED);
        }
        if (!evidenceService.isEnabled()) {
            return EkycResultMapper.simple(status, EkycResultCode.STORAGE_UNAVAILABLE);
        }
        BinaryFile front = decodeCardImage(request.cccdFrontBase64());
        BinaryFile back = decodeCardImage(request.cccdBackBase64());
        if (!rateLimitService.tryAcquireVerifySlot(keycloakUserId)) {
            return EkycResultMapper.simple(status, EkycResultCode.RATE_LIMITED);
        }

        DocumentResult document;
        try {
            document = aiEkycClient.analyzeDocument(aiProperties.internalTokenOrEmpty(),
                    toAiRequest(request, front, back, counterStore.currentRetakeCount(keycloakUserId)));
        } catch (FeignException e) {
            if (e.status() == 400 || e.status() == 422) {
                // AI không giải mã được ảnh (không phải lỗi hệ thống): người dùng chụp lại, không trả suất.
                log.info("AI từ chối ảnh thẻ: userProfileId={}, status={}", profile.getId(), e.status());
                return EkycResultMapper.simple(status, EkycResultCode.OCR_FAILED);
            }
            return aiUnavailable(keycloakUserId, profile, status, "HTTP " + e.status());
        } catch (RuntimeException e) {
            return aiUnavailable(keycloakUserId, profile, status, e.getClass().getSimpleName());
        }
        if (document == null || document.decision() == null) {
            return aiUnavailable(keycloakUserId, profile, status, "empty-response");
        }

        if ("RETAKE".equals(document.decision()) || !hasPortraitEmbedding(document)) {
            counterStore.incrementRetakeCount(keycloakUserId);
            log.info("Cần chụp lại thẻ: userProfileId={}, reasons={}", profile.getId(), retakeReasons(document));
            return EkycResultMapper.documentRetake(status, document.cardType(), retakeItems(document));
        }

        Optional<EkycResultCode> rejection = applyBusinessRules(profile, document);
        if (rejection.isPresent()) {
            log.info("Thẻ không đạt luật nghiệp vụ: userProfileId={}, code={}, cardType={}",
                    profile.getId(), rejection.get(), document.cardType());
            return EkycResultMapper.simple(status, rejection.get());
        }

        EkycIdentityDraft draft = buildDraft(UUID.randomUUID(), document);
        Instant now = clock.instant();
        StartedAttempt started = transactionTemplate.execute(
                tx -> attemptService.start(profile.getId(), consent.get().getId(), draft, now));
        discardObjects(started.staleObjectKeys(), now);

        List<StoredEvidence> stored;
        try {
            stored = evidenceService.storeCardImages(profile.getId(), draft.attemptId(), front, back);
        } catch (EkycStorageException e) {
            log.warn("Không lưu được ảnh thẻ, bỏ lượt: userProfileId={}, attemptId={}, cause={}",
                    profile.getId(), draft.attemptId(), e.getMessage());
            transactionTemplate.executeWithoutResult(tx -> attemptService.abandon(started.attemptId(), now));
            return EkycResultMapper.simple(status, EkycResultCode.STORAGE_UNAVAILABLE);
        }
        transactionTemplate.executeWithoutResult(tx -> attemptService.recordEvidence(started.attemptId(), stored, now));
        draftStore.save(keycloakUserId, draft);
        counterStore.resetRetakeCount(keycloakUserId);

        List<String> warnings = new ArrayList<>(draft.documentWarnings());
        CccdMatcher.softFieldWarnings(profile.getFullName(), profile.getDateOfBirth(),
                        draft.value(EkycDraftFieldName.FULL_NAME), draft.value(EkycDraftFieldName.DATE_OF_BIRTH))
                .stream().filter(code -> !warnings.contains(code)).forEach(warnings::add);
        log.info("Đã đọc thẻ, chờ liveness: userProfileId={}, attemptId={}, decision={}, cardType={}",
                profile.getId(), draft.attemptId(), draft.documentDecision(), draft.cardType());
        return EkycResultMapper.draftReady(status, draft, warnings);
    }

    // ── Bước xác nhận ───────────────────────────────────────────────

    public EkycResultResponse confirm(UUID keycloakUserId, Map<String, String> edits) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        EkycStatus status = profile.getEkycStatus();
        if (status == EkycStatus.VERIFIED) {
            return EkycResultMapper.alreadyVerified();
        }
        if (status == EkycStatus.MANUAL_REVIEW) {
            return EkycResultMapper.reviewPending();
        }
        EkycIdentityDraft stored = draftStore.find(keycloakUserId).orElse(null);
        if (stored == null) {
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }
        if (stored.livenessResult() == null || !stored.livenessResult().allowsConfirmation()) {
            return EkycResultMapper.simple(status, EkycResultCode.LIVENESS_REQUIRED, EkycNextStep.LIVENESS);
        }
        EkycIdentityDraft draft = applyEdits(stored, edits);

        Instant now = clock.instant();
        Optional<EkycResultCode> idProblem = identityService.checkIdNumber(
                profile, draft.value(EkycDraftFieldName.ID_NUMBER));
        if (idProblem.isPresent()) {
            log.warn("Số định danh không còn hợp lệ khi xác nhận: userProfileId={}, code={}",
                    profile.getId(), idProblem.get());
            abandonDraftAttempt(keycloakUserId, draft, now);
            return EkycResultMapper.simple(status, idProblem.get());
        }

        EkycAttempt attempt = attemptRepository.findByPublicId(draft.attemptId()).orElse(null);
        if (attempt == null || !attempt.isInProgress() || !attempt.getUserProfileId().equals(profile.getId())) {
            draftStore.remove(keycloakUserId);
            return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
        }

        boolean automatic = draft.documentDecision() == EkycDocumentDecision.AUTO_OK
                && draft.livenessResult() == EkycLivenessResult.PASS;
        List<EkycEvidence> liveEvidence = evidenceRepository.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                .filter(evidence -> !evidence.isDeleted())
                .toList();
        evidenceService.markFinal(liveEvidence.stream().map(EkycEvidence::getObjectKey).toList());
        StoredEvidence result;
        try {
            result = evidenceService.storeResult(profile.getId(), draft.attemptId(),
                    resultFile.build(attempt, draft, automatic ? "VERIFIED" : "MANUAL_REVIEW", liveEvidence, now));
        } catch (EkycStorageException e) {
            log.warn("Không ghi được result.json, giữ nguyên trạng thái: userProfileId={}, attemptId={}, cause={}",
                    profile.getId(), draft.attemptId(), e.getMessage());
            return EkycResultMapper.simple(status, EkycResultCode.STORAGE_UNAVAILABLE, EkycNextStep.CONFIRM);
        }

        ConfirmOutcome outcome;
        try {
            outcome = transactionTemplate.execute(tx -> {
                UserProfile fresh = userProfileRepository.findById(profile.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "id", profile.getId()));
                if (fresh.getEkycStatus() == EkycStatus.VERIFIED) {
                    return ConfirmOutcome.ALREADY_VERIFIED;
                }
                if (fresh.getEkycStatus() == EkycStatus.MANUAL_REVIEW) {
                    return ConfirmOutcome.ALREADY_IN_REVIEW;
                }
                EkycAttempt current = attemptRepository.findById(attempt.getId()).orElse(null);
                if (current == null || !current.isInProgress()) {
                    return ConfirmOutcome.ATTEMPT_CLOSED;
                }
                if (automatic) {
                    attemptService.completeVerified(fresh, current, draft, result, now);
                    return ConfirmOutcome.VERIFIED;
                }
                attemptService.submitForReview(fresh, current, draft, result, now);
                return ConfirmOutcome.SUBMITTED;
            });
        } catch (DataIntegrityViolationException e) {
            // Unique id_number_hash: tài khoản khác vừa xác nhận cùng số trong lúc bản nháp còn hiệu lực.
            return afterConflict(keycloakUserId, profile.getId(), result, EkycResultCode.ID_TAKEN);
        } catch (OptimisticLockingFailureException e) {
            return afterConflict(keycloakUserId, profile.getId(), result, EkycResultCode.DRAFT_EXPIRED);
        }

        switch (outcome) {
            case VERIFIED -> {
                sessionCleanup.clearAll(keycloakUserId);
                claimPublisher.publish(keycloakUserId, EkycStatus.VERIFIED);
                log.info("eKYC hoàn tất tự động: userProfileId={}, attemptId={}", profile.getId(), draft.attemptId());
                return EkycResultMapper.verified();
            }
            case SUBMITTED -> {
                sessionCleanup.clearAll(keycloakUserId);
                claimPublisher.publish(keycloakUserId, EkycStatus.MANUAL_REVIEW);
                log.info("eKYC chuyển duyệt tay: userProfileId={}, attemptId={}, decision={}, liveness={}",
                        profile.getId(), draft.attemptId(), draft.documentDecision(), draft.livenessResult());
                return EkycResultMapper.reviewPending();
            }
            case ALREADY_VERIFIED -> {
                sessionCleanup.clearAll(keycloakUserId);
                return EkycResultMapper.alreadyVerified();
            }
            case ALREADY_IN_REVIEW -> {
                sessionCleanup.clearAll(keycloakUserId);
                return EkycResultMapper.reviewPending();
            }
            default -> {
                // Lượt đã bị bỏ (quét lại ở thiết bị khác, rút đồng ý): result.json vừa ghi không còn ai dùng.
                evidenceService.deleteObjects(List.of(result.objectKey()));
                draftStore.remove(keycloakUserId);
                return EkycResultMapper.simple(status, EkycResultCode.DRAFT_EXPIRED);
            }
        }
    }

    // ── Luật nghiệp vụ trên kết quả đọc thẻ ────────────────────────

    /**
     * Thứ tự theo hợp đồng: loại thẻ → hết hạn → tuổi → số khác hồ sơ → số thuộc tài khoản khác.
     * Mọi mốc ngày tính theo lịch Việt Nam.
     */
    private Optional<EkycResultCode> applyBusinessRules(UserProfile profile, DocumentResult document) {
        DocumentFields fields = document.fields();
        String idNumber = trimToNull(valueOf(fields == null ? null : fields.idNumber()));
        String cardType = document.cardType();
        if ("CMND".equals(cardType)
                || ("UNKNOWN".equals(cardType) && idNumber == null)
                || (idNumber != null && ID_NUMBER_CMND.matcher(idNumber).matches())) {
            return Optional.of(EkycResultCode.CARD_UNSUPPORTED);
        }
        if (idNumber == null || !ID_NUMBER_12.matcher(idNumber).matches()) {
            // AI hứa luôn RETAKE khi thiếu số; tới được đây là kết quả bất thường — coi như chưa đọc được.
            return Optional.of(EkycResultCode.OCR_FAILED);
        }
        LocalDate today = LocalDate.now(clock.withZone(VIETNAM));
        FieldReading expiry = fields.expiryDate();
        boolean noExpiry = expiry != null && Boolean.TRUE.equals(expiry.noExpiry());
        if (!noExpiry && EkycIdentityService.parseDate(valueOf(expiry))
                .filter(date -> date.isBefore(today)).isPresent()) {
            return Optional.of(EkycResultCode.CARD_EXPIRED);
        }
        if (EkycIdentityService.parseDate(valueOf(fields.dateOfBirth()))
                .filter(dateOfBirth -> dateOfBirth.plusYears(ADULT_AGE).isAfter(today)).isPresent()) {
            return Optional.of(EkycResultCode.UNDERAGE);
        }
        return identityService.checkIdNumber(profile, idNumber);
    }

    private EkycIdentityDraft buildDraft(UUID attemptId, DocumentResult document) {
        DocumentFields source = document.fields();
        Map<String, EkycDraftField> fields = new LinkedHashMap<>();
        put(fields, EkycDraftFieldName.ID_NUMBER, source == null ? null : source.idNumber());
        put(fields, EkycDraftFieldName.FULL_NAME, source == null ? null : source.fullName());
        put(fields, EkycDraftFieldName.DATE_OF_BIRTH, source == null ? null : source.dateOfBirth());
        put(fields, EkycDraftFieldName.GENDER, source == null ? null : source.gender());
        put(fields, EkycDraftFieldName.ADDRESS, source == null ? null : source.address());
        put(fields, EkycDraftFieldName.PLACE_OF_ORIGIN, source == null ? null : source.placeOfOrigin());
        put(fields, EkycDraftFieldName.BIRTH_REGISTRATION_PLACE, source == null ? null : source.birthRegistrationPlace());
        put(fields, EkycDraftFieldName.ISSUE_DATE, source == null ? null : source.issueDate());
        put(fields, EkycDraftFieldName.EXPIRY_DATE, source == null ? null : source.expiryDate());

        boolean noExpiry = source != null && source.expiryDate() != null
                && Boolean.TRUE.equals(source.expiryDate().noExpiry());
        EkycDraftField expiry = fields.get(EkycDraftFieldName.EXPIRY_DATE.jsonName());
        if (noExpiry && expiry.value() == null) {
            // Thẻ "không thời hạn": hiển thị chữ cho người dùng; hồ sơ lưu id_expiry_date = null.
            fields.put(EkycDraftFieldName.EXPIRY_DATE.jsonName(), new EkycDraftField(NO_EXPIRY_TEXT, expiry.sources()));
        }
        String idNumber = trimToNull(fields.get(EkycDraftFieldName.ID_NUMBER.jsonName()).value());
        fields.put(EkycDraftFieldName.ID_NUMBER.jsonName(),
                new EkycDraftField(idNumber, fields.get(EkycDraftFieldName.ID_NUMBER.jsonName()).sources()));

        return new EkycIdentityDraft(attemptId, document.cardType(),
                EkycDocumentDecision.fromAi(document.decision()),
                document.reasons(), document.warnings(), document.checks(), document.thresholdsVersion(),
                fields, noExpiry, document.portrait().embeddingB64(),
                null, null, List.of(), null, null, 0, List.of());
    }

    private static void put(Map<String, EkycDraftField> fields, EkycDraftFieldName name, FieldReading reading) {
        if (reading == null) {
            fields.put(name.jsonName(), new EkycDraftField(null, List.of()));
            return;
        }
        String value = reading.value() == null || reading.value().isBlank() ? null : reading.value().trim();
        List<String> sources = reading.sources() == null ? List.of() : reading.sources().stream()
                .filter(sourceName -> sourceName != null && !sourceName.isBlank())
                .map(sourceName -> sourceName.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        fields.put(name.jsonName(), new EkycDraftField(value, sources));
    }

    // ── Sửa trường AI đọc ───────────────────────────────────────────

    /**
     * Chỉ trường đang {@code editable} được sửa; tên trường lạ hoặc trường có nguồn tất định bị từ chối
     * {@code EDIT_NOT_ALLOWED} để người dùng không ghi đè được số định danh hay ngày sinh đã kiểm chứng.
     */
    private static EkycIdentityDraft applyEdits(EkycIdentityDraft draft, Map<String, String> edits) {
        if (edits == null || edits.isEmpty()) {
            return draft;
        }
        Map<EkycDraftFieldName, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            EkycDraftFieldName name = EkycDraftFieldName.fromJsonName(edit.getKey()).orElse(null);
            if (name == null || !name.isEditable(draft.field(name))) {
                String field = edit.getKey() == null ? "" : edit.getKey().substring(0, Math.min(edit.getKey().length(), 40));
                throw new BusinessException(HttpStatus.BAD_REQUEST, "EDIT_NOT_ALLOWED",
                        "Trường \"" + field + "\" không được phép sửa.");
            }
            accepted.put(name, edit.getValue().trim());
        }
        return draft.withEdits(accepted);
    }

    // ── Helper ──────────────────────────────────────────────────────

    private EkycResultResponse aiUnavailable(UUID keycloakUserId, UserProfile profile, EkycStatus status, String cause) {
        // AI lỗi không phải lỗi của người dùng: trả suất rate limit và giữ nguyên trạng thái hồ sơ.
        rateLimitService.releaseVerifySlot(keycloakUserId);
        log.warn("Không gọi được AI đọc thẻ: userProfileId={}, cause={}", profile.getId(), cause);
        return EkycResultMapper.simple(status, EkycResultCode.AI_UNAVAILABLE);
    }

    /** Ngoài kết quả cuối: bỏ lượt và xoá object để không giữ ảnh thẻ của lượt không thể hoàn tất. */
    private void abandonDraftAttempt(UUID keycloakUserId, EkycIdentityDraft draft, Instant now) {
        attemptRepository.findByPublicId(draft.attemptId())
                .filter(EkycAttempt::isInProgress)
                .ifPresent(attempt -> {
                    transactionTemplate.executeWithoutResult(tx -> attemptService.abandon(attempt.getId(), now));
                    discardObjects(evidenceRepository.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                            .filter(evidence -> !evidence.isDeleted())
                            .map(EkycEvidence::getObjectKey)
                            .toList(), now);
                });
        sessionCleanup.clearAll(keycloakUserId);
    }

    /** Xoá object trên kho rồi đánh dấu {@code deleted_at}; object xoá hỏng để worker quét dọn thử lại. */
    private void discardObjects(List<String> keys, Instant now) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> deleted = evidenceService.deleteObjects(keys);
        if (!deleted.isEmpty()) {
            transactionTemplate.executeWithoutResult(tx -> attemptService.markDeleted(deleted, now));
        }
    }

    /**
     * Transaction xác nhận va chạm với request khác. Nếu bên kia đã hoàn tất thì trả theo trạng thái thật
     * (result.json dùng chung object key nên không xoá); nếu không thì xoá result.json mồ côi.
     */
    private EkycResultResponse afterConflict(UUID keycloakUserId, Long userProfileId, StoredEvidence result,
                                             EkycResultCode fallback) {
        UserProfile fresh = userProfileRepository.findById(userProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "id", userProfileId));
        if (fresh.getEkycStatus() == EkycStatus.VERIFIED) {
            sessionCleanup.clearAll(keycloakUserId);
            return EkycResultMapper.alreadyVerified();
        }
        if (fresh.getEkycStatus() == EkycStatus.MANUAL_REVIEW) {
            sessionCleanup.clearAll(keycloakUserId);
            return EkycResultMapper.reviewPending();
        }
        log.warn("Xác nhận eKYC va chạm, không ghi hồ sơ: userProfileId={}, code={}", userProfileId, fallback);
        evidenceService.deleteObjects(List.of(result.objectKey()));
        return EkycResultMapper.simple(fresh.getEkycStatus(), fallback);
    }

    private static AiEkycClient.DocumentRequest toAiRequest(EkycVerifyRequest request, BinaryFile front,
                                                            BinaryFile back, int retakeCount) {
        EkycVerifyRequest.QrPayload qr = request.qr();
        String qrText = qr == null || qr.text() == null || qr.text().isBlank() ? null : qr.text();
        return new AiEkycClient.DocumentRequest(
                Base64.getEncoder().encodeToString(front.bytes()),
                Base64.getEncoder().encodeToString(back.bytes()),
                qrText,
                qrText == null ? null : qr.side(),
                request.frontSourceOrDefault(),
                request.backSourceOrDefault(),
                retakeCount);
    }

    /**
     * Giải mã ảnh thẻ và kiểm magic bytes JPEG/PNG trước khi tiêu suất rate limit. Server phải lưu đúng
     * những byte đã phân tích nên ảnh được giải mã một lần ở đây và dùng chung cho AI và kho lưu trữ.
     */
    static BinaryFile decodeCardImage(String base64) {
        String cleaned = WHITESPACE.matcher(DATA_URI_PREFIX.matcher(base64.trim()).replaceFirst("")).replaceAll("");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            throw invalidImage();
        }
        if (isJpeg(bytes)) {
            return new BinaryFile(bytes, "image/jpeg", "jpg");
        }
        if (isPng(bytes)) {
            return new BinaryFile(bytes, "image/png", "png");
        }
        throw invalidImage();
    }

    private static BusinessException invalidImage() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE",
                "Ảnh thẻ không hợp lệ: chỉ nhận ảnh JPEG hoặc PNG.");
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
    }

    /** Thiếu embedding chân dung thì không so được khuôn mặt ở bước sau — luôn phải chụp lại mặt trước. */
    private static boolean hasPortraitEmbedding(DocumentResult document) {
        return document.portrait() != null
                && Boolean.TRUE.equals(document.portrait().found())
                && document.portrait().embeddingB64() != null
                && !document.portrait().embeddingB64().isBlank();
    }

    private static List<RetakeItem> retakeItems(DocumentResult document) {
        if (!"RETAKE".equals(document.decision())) {
            return List.of(new RetakeItem("FRONT", "PORTRAIT_UNUSABLE", EkycMessages.retake("PORTRAIT_UNUSABLE")));
        }
        if (document.retake() == null) {
            return List.of();
        }
        return document.retake().stream()
                .filter(hint -> hint != null && hint.reason() != null)
                .map(hint -> new RetakeItem(hint.side(), hint.reason(), EkycMessages.retake(hint.reason())))
                .toList();
    }

    private static List<String> retakeReasons(DocumentResult document) {
        return retakeItems(document).stream().map(item -> item.side() + ":" + item.reason()).toList();
    }

    private static String valueOf(FieldReading reading) {
        return reading == null ? null : reading.value();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UserProfile findProfileOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }

    private enum ConfirmOutcome {
        VERIFIED,
        SUBMITTED,
        ALREADY_VERIFIED,
        ALREADY_IN_REVIEW,
        ATTEMPT_CLOSED
    }
}
