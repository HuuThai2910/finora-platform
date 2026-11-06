package com.finora.user.service.impl;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.EkycConsent;
import com.finora.user.domain.entity.EkycEvidence;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.EkycAttemptStatus;
import com.finora.user.dto.request.EkycConsentAcceptRequest;
import com.finora.user.dto.response.EkycConsentResponse;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycConsentRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.EkycConsentDocument;
import com.finora.user.service.EkycConsentService;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycSessionCleanup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Điều khoản xử lý dữ liệu eKYC: xem, đồng ý, rút lại (EKYC-002 mục 3.1; kiểm soát {@code LEGAL-DATA-01}).
 * <p>
 * Đồng ý "còn hiệu lực" khi bản ghi mới nhất chưa rút khớp cả phiên bản lẫn SHA-256 nội dung hiện hành.
 * Các bước quét thẻ và xin thử thách tự kiểm điều này trong database — không tin cờ client gửi lên.
 * <p>
 * Rút lại: (1) một transaction ghi {@code withdrawn_at}, bỏ lượt đang mở, xoá bản nháp mã hoá và đưa hồ
 * sơ về chưa xác minh — số định danh (hash + bản mã hoá) được <b>giữ</b> để một CCCD không mở nhiều tài
 * khoản; (2) sau commit mới dọn kho tạm, ghi claim Keycloak và xoá object trên kho theo tiền tố người dùng;
 * (3) xoá được thì đánh dấu {@code deleted_at}, xoá hỏng thì worker quét dọn thử lại (bằng chứng tạo
 * trước lần rút đồng ý).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EkycConsentServiceImpl implements EkycConsentService {

    private final UserProfileRepository userProfileRepository;
    private final EkycConsentRepository ekycConsentRepository;
    private final EkycAttemptRepository ekycAttemptRepository;
    private final EkycEvidenceRepository ekycEvidenceRepository;
    private final EkycConsentDocument ekycConsentDocument;
    private final EkycEvidenceService ekycEvidenceService;
    private final EkycSessionCleanup ekycSessionCleanup;
    private final EkycClaimPublisher ekycClaimPublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public EkycConsentResponse getConsent(UUID keycloakUserId) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        return toResponse(findActiveConsent(profile.getId()).orElse(null));
    }

    @Override
    public Optional<EkycConsent> findActiveConsent(Long userProfileId) {
        return ekycConsentRepository.findFirstByUserProfileIdAndWithdrawnAtIsNullOrderByAcceptedAtDescIdDesc(userProfileId)
                .filter(consent -> consent.isActiveFor(ekycConsentDocument.version(), ekycConsentDocument.textSha256()));
    }

    @Override
    public EkycConsentResponse accept(UUID keycloakUserId, EkycConsentAcceptRequest request) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        String hash = request.textSha256().toLowerCase(Locale.ROOT);
        if (!ekycConsentDocument.version().equals(request.version()) || !ekycConsentDocument.textSha256().equals(hash)) {
            throw new BusinessException(HttpStatus.CONFLICT, "CONSENT_VERSION_STALE",
                    "Điều khoản đã được cập nhật. Vui lòng tải lại và đọc bản mới trước khi đồng ý.");
        }
        Optional<EkycConsent> active = findActiveConsent(profile.getId());
        if (active.isPresent()) {
            return toResponse(active.get());
        }
        String platform = request.platform() == null ? null : request.platform().toLowerCase(Locale.ROOT);
        EkycConsent saved = ekycConsentRepository.save(EkycConsent.accept(profile.getId(), ekycConsentDocument.version(),
                EkycConsentDocument.PURPOSES, ekycConsentDocument.textSha256(), platform, request.appVersion(),
                clock.instant()));
        log.info("Ghi nhận đồng ý điều khoản eKYC: userProfileId={}, version={}, platform={}",
                profile.getId(), saved.getVersion(), platform);
        return toResponse(saved);
    }

    @Override
    public EkycConsentResponse withdraw(UUID keycloakUserId) {
        UserProfile profile = findProfileOrThrow(keycloakUserId);
        Long profileId = profile.getId();
        Instant now = clock.instant();

        StatusChange change = transactionTemplate.execute(status -> {
            UserProfile fresh = userProfileRepository.findById(profileId)
                    .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "id", profileId));
            EkycStatus before = fresh.getEkycStatus();
            ekycConsentRepository.findByUserProfileIdAndWithdrawnAtIsNull(profileId)
                    .forEach(consent -> consent.withdraw(now));
            Map<Long, EkycAttempt> touched = new LinkedHashMap<>();
            ekycAttemptRepository.findByUserProfileIdAndStatusIn(profileId,
                            List.of(EkycAttemptStatus.IN_PROGRESS, EkycAttemptStatus.MANUAL_REVIEW))
                    .forEach(attempt -> touched.put(attempt.getId(), attempt));
            ekycAttemptRepository.findByUserProfileIdAndDraftEncryptedIsNotNull(profileId)
                    .forEach(attempt -> touched.putIfAbsent(attempt.getId(), attempt));
            touched.values().forEach(attempt -> attempt.withdraw(now));
            fresh.resetEkycAfterConsentWithdrawal();
            return new StatusChange(before, fresh.getEkycStatus());
        });

        // Sau commit: dọn trạng thái tạm, cập nhật claim, xoá bằng chứng trên kho.
        ekycSessionCleanup.clearAll(keycloakUserId);
        if (change != null && change.before() != change.after()) {
            ekycClaimPublisher.publish(keycloakUserId, change.after());
        }
        if (ekycEvidenceService.deleteUserObjects(profileId)) {
            transactionTemplate.executeWithoutResult(status -> {
                List<Long> ids = ekycEvidenceRepository
                        .findLiveByUserProfileId(profileId, ekycEvidenceService.envPrefix() + "%").stream()
                        .map(EkycEvidence::getId)
                        .toList();
                if (!ids.isEmpty()) {
                    ekycEvidenceRepository.markDeleted(ids, now);
                }
            });
        }
        log.info("Người dùng rút lại đồng ý eKYC: userProfileId={}, ekycStatus={}",
                profileId, change == null ? null : change.after());
        return toResponse(null);
    }

    private EkycConsentResponse toResponse(EkycConsent active) {
        return new EkycConsentResponse(
                ekycConsentDocument.version(),
                EkycConsentDocument.TITLE,
                ekycConsentDocument.body(),
                EkycConsentDocument.CONSENT_LABEL,
                EkycConsentDocument.PURPOSES,
                ekycConsentDocument.textSha256(),
                active != null,
                active == null ? null : active.getAcceptedAt());
    }

    private UserProfile findProfileOrThrow(UUID keycloakUserId) {
        return userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
    }

    private record StatusChange(EkycStatus before, EkycStatus after) {
    }
}
