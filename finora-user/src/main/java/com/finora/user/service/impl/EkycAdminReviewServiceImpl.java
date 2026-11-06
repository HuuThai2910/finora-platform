package com.finora.user.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.EkycConsent;
import com.finora.user.domain.entity.EkycEvidence;
import com.finora.user.domain.entity.EkycEvidenceAccessLog;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.EkycAttemptStatus;
import com.finora.user.domain.enums.EkycDraftFieldName;
import com.finora.user.domain.model.EkycIdentityDraft;
import com.finora.user.dto.response.EkycAttemptDetailResponse.EvidenceItem;
import com.finora.user.dto.response.EkycAttemptDetailResponse;
import com.finora.user.dto.response.EkycAttemptSummaryResponse;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.integration.storage.StoredObject;
import com.finora.user.mapper.EkycResultMapper;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycAttemptSummaryRow;
import com.finora.user.repository.EkycConsentRepository;
import com.finora.user.repository.EkycEvidenceAccessLogRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycAdminReviewService;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycIdentityService;
import com.finora.user.util.EkycMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Trang Duyệt eKYC của admin (EKYC-002 mục 3.7): danh sách lượt, chi tiết, xem bằng chứng, duyệt/từ chối.
 * <p>
 * Mỗi lần mở chi tiết (xem bản nháp đã giải mã) hoặc mở một file đều ghi {@code ekyc_evidence_access_log}
 * trong cùng transaction với lần đọc — điều khoản cam kết "mỗi lần xem đều được ghi lại".
 * <p>
 * Duyệt/từ chối chỉ áp dụng cho lượt {@code MANUAL_REVIEW}. Hai nhân viên bấm cùng lúc: cả hai đọc
 * version cũ, người flush sau nhận lỗi optimistic lock → {@code 409 ATTEMPT_ALREADY_DECIDED} và
 * transaction của họ rollback toàn bộ (kể cả thay đổi hồ sơ).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycAdminReviewServiceImpl implements EkycAdminReviewService {

    static final int MAX_PAGE_SIZE = 100;

    private final EkycAttemptRepository ekycAttemptRepository;
    private final EkycEvidenceRepository ekycEvidenceRepository;
    private final EkycEvidenceAccessLogRepository ekycEvidenceAccessLogRepository;
    private final EkycConsentRepository ekycConsentRepository;
    private final UserProfileRepository userProfileRepository;
    private final EkycAttemptService ekycAttemptService;
    private final EkycIdentityService ekycIdentityService;
    private final EkycEvidenceService ekycEvidenceService;
    private final EkycClaimPublisher ekycClaimPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public PageResponse<EkycAttemptSummaryResponse> list(String status, int page, int size) {
        EkycAttemptStatus filter = parseStatus(status);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<EkycAttemptSummaryRow> rows = filter == null
                ? ekycAttemptRepository.findAllSummaries(pageable)
                : ekycAttemptRepository.findSummariesByStatus(filter, pageable);
        return PageResponse.<EkycAttemptSummaryResponse>builder()
                .content(rows.getContent().stream().map(row -> toSummary(row.attempt(), row.email())).toList())
                .page(rows.getNumber())
                .size(rows.getSize())
                .totalElements(rows.getTotalElements())
                .totalPages(rows.getTotalPages())
                .last(rows.isLast())
                .build();
    }

    @Override
    public PageResponse<EkycAttemptSummaryResponse> listForUser(Long userProfileId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        Page<EkycAttemptSummaryRow> rows = ekycAttemptRepository.findSummariesByUserProfileId(userProfileId, pageable);
        return PageResponse.<EkycAttemptSummaryResponse>builder()
                .content(rows.getContent().stream().map(row -> toSummary(row.attempt(), row.email())).toList())
                .page(rows.getNumber())
                .size(rows.getSize())
                .totalElements(rows.getTotalElements())
                .totalPages(rows.getTotalPages())
                .last(rows.isLast())
                .build();
    }

    @Override
    public EkycAttemptDetailResponse detail(String attemptId, UUID adminKeycloakId) {
        UUID publicId = parseAttemptId(attemptId);
        return transactionTemplate.execute(tx -> {
            EkycAttempt attempt = findAttempt(publicId);
            ekycEvidenceAccessLogRepository.save(
                    EkycEvidenceAccessLog.of(attempt.getId(), null, adminKeycloakId, clock.instant()));
            return toDetail(attempt);
        });
    }

    @Override
    public EvidenceDownload openEvidence(String attemptId, String evidenceId, UUID adminKeycloakId) {
        UUID publicId = parseAttemptId(attemptId);
        Long numericEvidenceId = parseEvidenceId(evidenceId);
        EkycEvidence evidence = transactionTemplate.execute(tx -> {
            EkycAttempt attempt = findAttempt(publicId);
            EkycEvidence found = ekycEvidenceRepository.findByIdAndAttemptId(numericEvidenceId, attempt.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Bằng chứng eKYC", "evidenceId", evidenceId));
            if (found.isDeleted()) {
                throw evidenceDeleted();
            }
            ekycEvidenceAccessLogRepository.save(EkycEvidenceAccessLog.of(attempt.getId(), found.getId(), adminKeycloakId,
                    clock.instant()));
            return found;
        });
        try {
            StoredObject object = ekycEvidenceService.open(evidence.getObjectKey());
            log.info("Admin mở bằng chứng eKYC: attemptId={}, evidenceId={}, kind={}, admin={}",
                    publicId, evidence.getId(), evidence.getKind(), adminKeycloakId);
            long length = object.contentLength() >= 0 ? object.contentLength() : evidence.getSizeBytes();
            return new EvidenceDownload(object, evidence.getContentType(), length);
        } catch (EkycStorageException e) {
            if (e.isNotFound()) {
                throw evidenceDeleted();
            }
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, EkycResultCode.STORAGE_UNAVAILABLE.name(),
                    EkycMessages.result(EkycResultCode.STORAGE_UNAVAILABLE));
        }
    }

    @Override
    public EkycAttemptDetailResponse approve(String attemptId, UUID adminKeycloakId, String note) {
        UUID publicId = parseAttemptId(attemptId);
        Instant now = clock.instant();
        Decision decision;
        try {
            decision = transactionTemplate.execute(tx -> {
                EkycAttempt attempt = findAttempt(publicId);
                UserProfile profile = requireReviewable(attempt);
                EkycIdentityDraft draft = ekycAttemptService.readDraft(attempt.getDraftEncrypted());
                if (draft == null) {
                    throw new BusinessException(HttpStatus.CONFLICT, "ATTEMPT_DRAFT_MISSING",
                            "Lượt xác minh không còn bản nháp để duyệt.");
                }
                Optional<EkycResultCode> idProblem = ekycIdentityService.checkIdNumber(
                        profile, draft.value(EkycDraftFieldName.ID_NUMBER));
                if (idProblem.isPresent()) {
                    throw new BusinessException(HttpStatus.CONFLICT, idProblem.get().name(),
                            EkycMessages.result(idProblem.get()));
                }
                ekycIdentityService.applyIdentity(profile, draft);
                boolean livenessVerified = attempt.getLivenessResult() != null
                        && attempt.getLivenessResult().allowsConfirmation();
                profile.markEkycVerified(attempt.getFaceMatchScore(), livenessVerified, now);
                attempt.approve(adminKeycloakId, trimToNull(note), now);
                ekycAttemptService.ensureCicTask(profile.getId(), now);
                ekycAttemptRepository.saveAndFlush(attempt);
                return new Decision(profile.getKeycloakUserId(), EkycStatus.VERIFIED, toDetail(attempt));
            });
        } catch (OptimisticLockingFailureException e) {
            throw alreadyDecided();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, EkycResultCode.ID_TAKEN.name(),
                    EkycMessages.result(EkycResultCode.ID_TAKEN));
        }
        ekycClaimPublisher.publish(decision.keycloakUserId(), decision.status());
        log.info("Admin duyệt eKYC: attemptId={}, admin={}", publicId, adminKeycloakId);
        return decision.detail();
    }

    @Override
    public EkycAttemptDetailResponse reject(String attemptId, UUID adminKeycloakId, String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REVIEW_NOTE_REQUIRED",
                    "Vui lòng ghi lý do từ chối.");
        }
        UUID publicId = parseAttemptId(attemptId);
        Instant now = clock.instant();
        Decision decision;
        try {
            decision = transactionTemplate.execute(tx -> {
                EkycAttempt attempt = findAttempt(publicId);
                UserProfile profile = requireReviewable(attempt);
                attempt.reject(adminKeycloakId, note.trim(), now);
                profile.markEkycFailed(now);
                ekycAttemptRepository.saveAndFlush(attempt);
                return new Decision(profile.getKeycloakUserId(), EkycStatus.FAILED, toDetail(attempt));
            });
        } catch (OptimisticLockingFailureException e) {
            throw alreadyDecided();
        }
        ekycClaimPublisher.publish(decision.keycloakUserId(), decision.status());
        log.info("Admin từ chối eKYC: attemptId={}, admin={}", publicId, adminKeycloakId);
        return decision.detail();
    }

    // ── Helper ──────────────────────────────────────────────────────

    /** Chỉ lượt đang chờ duyệt của hồ sơ đang chờ duyệt mới được quyết định. */
    private UserProfile requireReviewable(EkycAttempt attempt) {
        if (attempt.getStatus() != EkycAttemptStatus.MANUAL_REVIEW) {
            throw alreadyDecided();
        }
        UserProfile profile = userProfileRepository.findById(attempt.getUserProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "id", attempt.getUserProfileId()));
        if (profile.getEkycStatus() != EkycStatus.MANUAL_REVIEW) {
            throw alreadyDecided();
        }
        return profile;
    }

    private EkycAttemptSummaryResponse toSummary(EkycAttempt attempt, String email) {
        List<String> livenessReasons = ekycAttemptService.readCodes(attempt.getLivenessReasons());
        return new EkycAttemptSummaryResponse(
                attempt.getPublicId(),
                attempt.getUserProfileId(),
                email,
                attempt.getStatus().name(),
                attempt.getCardType(),
                nameOf(attempt.getDocumentDecision()),
                nameOf(attempt.getLivenessResult()),
                attempt.getFaceMatchScore(),
                EkycAttemptService.faceMatchZone(livenessReasons),
                ekycAttemptService.readCodes(attempt.getDocumentReasons()),
                attempt.getCreatedAt(),
                attempt.getSubmittedAt(),
                attempt.getDecidedAt(),
                attempt.getReviewNote());
    }

    private EkycAttemptDetailResponse toDetail(EkycAttempt attempt) {
        String email = userProfileRepository.findById(attempt.getUserProfileId())
                .map(UserProfile::getEmail)
                .orElse(null);
        String consentVersion = attempt.getConsentId() == null
                ? null
                : ekycConsentRepository.findById(attempt.getConsentId()).map(EkycConsent::getVersion).orElse(null);
        List<EvidenceItem> evidence = ekycEvidenceRepository.findByAttemptIdOrderByIdAsc(attempt.getId()).stream()
                .map(item -> new EvidenceItem(item.getId(), item.getKind().name(), item.getLabel(),
                        item.getContentType(), item.getSizeBytes(), item.isDeleted()))
                .toList();
        List<String> livenessReasons = ekycAttemptService.readCodes(attempt.getLivenessReasons());
        // Chỉ lưu UUID Keycloak của người duyệt; tra hồ sơ lúc đọc để hiện email/họ tên. Admin tạo thẳng trên
        // Keycloak không có hồ sơ FINORA thì để null — web hiện mã rút gọn như trước.
        Optional<UserProfile> reviewer = attempt.getReviewerKeycloakId() == null
                ? Optional.empty()
                : userProfileRepository.findByKeycloakUserId(attempt.getReviewerKeycloakId());
        return new EkycAttemptDetailResponse(
                attempt.getPublicId(),
                attempt.getUserProfileId(),
                email,
                attempt.getStatus().name(),
                attempt.getCardType(),
                nameOf(attempt.getDocumentDecision()),
                nameOf(attempt.getLivenessResult()),
                attempt.getFaceMatchScore(),
                EkycAttemptService.faceMatchZone(livenessReasons),
                ekycAttemptService.readCodes(attempt.getDocumentReasons()),
                attempt.getCreatedAt(),
                attempt.getSubmittedAt(),
                EkycResultMapper.toDraftView(ekycAttemptService.readDraft(attempt.getDraftEncrypted())),
                livenessReasons,
                consentVersion,
                evidence,
                attempt.getReviewNote(),
                attempt.getDecidedAt(),
                attempt.getReviewerKeycloakId(),
                reviewer.map(UserProfile::getEmail).orElse(null),
                reviewer.map(UserProfile::getFullName).filter(name -> !name.isBlank()).orElse(null));
    }

    private EkycAttempt findAttempt(UUID publicId) {
        return ekycAttemptRepository.findByPublicId(publicId)
                .orElseThrow(() -> new ResourceNotFoundException("Lượt eKYC", "attemptId", publicId));
    }

    private static EkycAttemptStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return EkycAttemptStatus.MANUAL_REVIEW;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized)) {
            return null;
        }
        try {
            return EkycAttemptStatus.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS",
                    "Trạng thái lượt eKYC không hợp lệ.");
        }
    }

    /** Mã lượt sai định dạng thì không thể tồn tại — trả 404 như không tìm thấy. */
    private static UUID parseAttemptId(String attemptId) {
        try {
            return UUID.fromString(attemptId.trim());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResourceNotFoundException("Lượt eKYC", "attemptId", attemptId);
        }
    }

    private static Long parseEvidenceId(String evidenceId) {
        try {
            return Long.parseLong(evidenceId.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new ResourceNotFoundException("Bằng chứng eKYC", "evidenceId", evidenceId);
        }
    }

    private static BusinessException alreadyDecided() {
        return new BusinessException(HttpStatus.CONFLICT, "ATTEMPT_ALREADY_DECIDED",
                "Lượt xác minh này đã được xử lý.");
    }

    private static BusinessException evidenceDeleted() {
        return new BusinessException(HttpStatus.GONE, "EVIDENCE_DELETED",
                "Bằng chứng đã bị xoá theo hạn lưu hoặc theo yêu cầu của người dùng.");
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Decision(UUID keycloakUserId, EkycStatus status, EkycAttemptDetailResponse detail) {
    }
}
