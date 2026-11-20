package com.finora.user.service.impl;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.EkycEvidence;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.EkycAttemptStatus;
import com.finora.user.dto.response.MyEkycStatusResponse;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycReviewWithdrawalService;
import com.finora.user.service.EkycSessionCleanup;
import com.finora.user.util.EkycMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Rút lại hồ sơ eKYC đang chờ duyệt tay.
 * <p>
 * <b>Tranh chấp với nhân viên duyệt cùng lúc</b>: cả hai bên đều ghi dòng {@code ekyc_attempts} có {@code @Version}
 * và flush trong transaction. Bên commit trước thắng; bên sau nhận lỗi optimistic lock, transaction của họ rollback
 * toàn bộ (kể cả thay đổi hồ sơ) và nhận 409 — người dùng thấy "Hồ sơ vừa được nhân viên xử lý", nhân viên thấy
 * "Người dùng đã rút lại hồ sơ này" ({@link EkycAdminReviewServiceImpl}).
 * <p>
 * Claim Keycloak, dọn kho tạm và xoá object bằng chứng chỉ chạy sau commit; object xoá lỗi để worker quét dọn
 * (lượt {@code WITHDRAWN} được quét như lượt bỏ dở).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycReviewWithdrawalServiceImpl implements EkycReviewWithdrawalService {

    private final UserProfileRepository userProfileRepository;
    private final EkycAttemptRepository ekycAttemptRepository;
    private final EkycEvidenceRepository ekycEvidenceRepository;
    private final EkycEvidenceService ekycEvidenceService;
    private final EkycAttemptService ekycAttemptService;
    private final EkycSessionCleanup ekycSessionCleanup;
    private final EkycClaimPublisher ekycClaimPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public MyEkycStatusResponse withdraw(UUID keycloakUserId) {
        UserProfile profile = userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
        Long profileId = profile.getId();
        Instant now = clock.instant();

        EkycAttempt withdrawn;
        try {
            withdrawn = transactionTemplate.execute(tx -> {
                UserProfile fresh = userProfileRepository.findById(profileId)
                        .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "id", profileId));
                EkycAttempt latest = ekycAttemptRepository.findFirstByUserProfileIdOrderByCreatedAtDescIdDesc(profileId)
                        .orElseThrow(EkycReviewWithdrawalServiceImpl::notWithdrawable);
                if (latest.getStatus() == EkycAttemptStatus.APPROVED || latest.getStatus() == EkycAttemptStatus.REJECTED) {
                    throw justDecided();
                }
                if (latest.getStatus() != EkycAttemptStatus.MANUAL_REVIEW || fresh.getEkycStatus() != EkycStatus.MANUAL_REVIEW) {
                    throw notWithdrawable();
                }
                latest.withdrawFromReview(now);
                fresh.resetEkycAfterReviewWithdrawal();
                ekycAttemptRepository.saveAndFlush(latest);
                return latest;
            });
        } catch (OptimisticLockingFailureException e) {
            throw justDecided();
        }

        ekycSessionCleanup.clearAll(keycloakUserId);
        ekycClaimPublisher.publish(keycloakUserId, EkycStatus.PENDING);
        discardEvidence(withdrawn.getId(), now);
        log.info("Người dùng rút lại hồ sơ eKYC chờ duyệt: userProfileId={}, attemptId={}",
                profileId, withdrawn.getPublicId());
        return new MyEkycStatusResponse(EkycStatus.PENDING, withdrawn.getStatus().name(),
                withdrawn.getSubmittedAt(), withdrawn.getDecidedAt(), null);
    }

    /** Hồ sơ rút lại không còn ai duyệt: xoá ảnh/video ngay như lượt bỏ dở; object xoá lỗi để worker thử lại. */
    private void discardEvidence(Long attemptId, Instant now) {
        List<String> keys = ekycEvidenceRepository.findByAttemptIdInAndDeletedAtIsNull(List.of(attemptId)).stream()
                .map(EkycEvidence::getObjectKey)
                .toList();
        if (keys.isEmpty()) {
            return;
        }
        List<String> deleted = ekycEvidenceService.deleteObjects(keys);
        if (!deleted.isEmpty()) {
            transactionTemplate.executeWithoutResult(tx -> ekycAttemptService.markDeleted(deleted, now));
        }
    }

    private static BusinessException notWithdrawable() {
        return new BusinessException(HttpStatus.CONFLICT, "EKYC_NOT_WITHDRAWABLE", EkycMessages.NOTHING_TO_WITHDRAW);
    }

    private static BusinessException justDecided() {
        return new BusinessException(HttpStatus.CONFLICT, "ATTEMPT_ALREADY_DECIDED", EkycMessages.REVIEW_JUST_DECIDED);
    }
}
