package com.finora.user.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.EkycAttemptStatus;
import com.finora.user.dto.response.KycSummaryResponse;
import com.finora.user.dto.response.MyEkycStatusResponse;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KycSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Đọc tóm tắt eKYC của chính người dùng (W2-KYC). Dữ liệu lấy từ DB User tại thời điểm gọi, không từ claim trong token.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KycSummaryServiceImpl implements KycSummaryService {

    /**
     * Tuổi tính theo ngày Việt Nam: người sinh ngày 17/10 đủ tuổi từ 00:00 giờ Việt Nam ngày 17/10, không phải từ 07:00
     * như khi lấy ngày theo UTC.
     */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserProfileRepository userProfileRepository;
    private final EkycAttemptRepository ekycAttemptRepository;
    private final Clock clock;

    @Override
    public KycSummaryResponse summaryOf(UUID keycloakUserId) {
        UserProfile profile = userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
        return new KycSummaryResponse(
                String.valueOf(profile.getId()),
                profile.getEkycStatus(),
                profile.isDocumentVerified(),
                profile.getEkycCompletedAt(),
                ageOf(profile.getDateOfBirth()));
    }

    @Override
    public MyEkycStatusResponse myEkycStatus(UUID keycloakUserId) {
        UserProfile profile = userProfileRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hồ sơ người dùng", "keycloakUserId", keycloakUserId));
        Optional<EkycAttempt> latest = ekycAttemptRepository
                .findFirstByUserProfileIdOrderByCreatedAtDescIdDesc(profile.getId());
        if (latest.isEmpty()) {
            return new MyEkycStatusResponse(profile.getEkycStatus(), null, null, null, null);
        }
        EkycAttempt attempt = latest.get();
        // Chỉ lượt bị từ chối mới có lý do cho khách; ghi chú lúc duyệt là ghi chú nội bộ.
        String reason = attempt.getStatus() == EkycAttemptStatus.REJECTED ? attempt.getReviewNote() : null;
        return new MyEkycStatusResponse(profile.getEkycStatus(), attempt.getStatus().name(),
                attempt.getSubmittedAt(), attempt.getDecidedAt(), reason);
    }

    Integer ageOf(LocalDate dateOfBirth) {
        if (dateOfBirth == null) return null;
        return Period.between(dateOfBirth, LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE)).getYears();
    }
}
