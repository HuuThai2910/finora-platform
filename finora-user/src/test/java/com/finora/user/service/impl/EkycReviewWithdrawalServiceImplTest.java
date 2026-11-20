package com.finora.user.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.EkycEvidence;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.EkycAttemptStatus;
import com.finora.user.domain.enums.EkycDocumentDecision;
import com.finora.user.domain.enums.EkycEvidenceKind;
import com.finora.user.domain.enums.EkycEvidenceStage;
import com.finora.user.domain.enums.EkycLivenessResult;
import com.finora.user.dto.response.MyEkycStatusResponse;
import com.finora.user.repository.CicMappingTaskRepository;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycSessionCleanup;
import com.finora.user.util.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Người dùng rút lại hồ sơ eKYC chờ duyệt: chuyển trạng thái, xoá bằng chứng, tranh chấp với nhân viên. */
@ExtendWith(MockitoExtension.class)
class EkycReviewWithdrawalServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");
    private static final Long PROFILE_ID = 7L;
    private static final UUID ATTEMPT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private EkycAttemptRepository attemptRepository;
    @Mock private EkycEvidenceRepository evidenceRepository;
    @Mock private CicMappingTaskRepository cicMappingTaskRepository;
    @Mock private EkycEvidenceService evidenceService;
    @Mock private EkycSessionCleanup sessionCleanup;
    @Mock private EkycClaimPublisher claimPublisher;

    private final ObjectMapper objectMapper = EkycTestData.objectMapper();
    private EkycReviewWithdrawalServiceImpl service;
    private UserProfile profile;
    private EkycAttempt attempt;

    @BeforeEach
    void setUp() {
        EkycAttemptService attemptService = new EkycAttemptServiceImpl(attemptRepository, evidenceRepository,
                cicMappingTaskRepository, new EkycIdentityServiceImpl(userProfileRepository, EkycTestData.cryptoProperties()),
                objectMapper);
        service = new EkycReviewWithdrawalServiceImpl(userProfileRepository, attemptRepository, evidenceRepository,
                evidenceService, attemptService, sessionCleanup, claimPublisher, EkycTestData.transactionTemplate(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        profile = UserProfile.builder().id(PROFILE_ID).keycloakUserId(USER_ID).email("nguoidung@example.test")
                .ekycStatus(EkycStatus.MANUAL_REVIEW).build();
        attempt = EkycTestData.attempt(100L, ATTEMPT_ID, PROFILE_ID, EkycDocumentDecision.NEEDS_REVIEW, NOW);
        attempt.recordLiveness(EkycLivenessResult.REVIEW, "[\"FACE_MATCH_GRAY\"]", 0.41, "doc,liveness", NOW);
        attempt.submitForReview(attemptService.draftJson(
                EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.NEEDS_REVIEW, EkycLivenessResult.REVIEW)
                        .withoutBiometrics()), NOW);
        lenient().when(userProfileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(userProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        lenient().when(attemptRepository.findFirstByUserProfileIdOrderByCreatedAtDescIdDesc(PROFILE_ID))
                .thenReturn(Optional.of(attempt));
    }

    @Test
    void withdrawResetsProfileClearsDraftPublishesClaimAndDeletesEvidence() {
        EkycEvidence front = EkycTestData.evidence(11L, 100L, EkycEvidenceKind.CCCD_FRONT, "k/front", EkycEvidenceStage.FINAL, NOW);
        EkycEvidence video = EkycTestData.evidence(12L, 100L, EkycEvidenceKind.LIVENESS_VIDEO, "k/video", EkycEvidenceStage.FINAL, NOW);
        when(evidenceRepository.findByAttemptIdInAndDeletedAtIsNull(List.of(100L))).thenReturn(List.of(front, video));
        // Một object xoá lỗi: chỉ đánh dấu object đã xoá được, phần còn lại để worker quét dọn.
        when(evidenceService.deleteObjects(List.of("k/front", "k/video"))).thenReturn(List.of("k/front"));

        MyEkycStatusResponse response = service.withdraw(USER_ID);

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.WITHDRAWN);
        assertThat(attempt.getDraftEncrypted()).as("bản nháp mã hoá bị xoá ngay").isNull();
        assertThat(attempt.getDecidedAt()).isEqualTo(NOW);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
        assertThat(response.ekycStatus()).isEqualTo(EkycStatus.PENDING);
        assertThat(response.latestAttemptStatus()).isEqualTo("WITHDRAWN");
        assertThat(response.rejectionReason()).isNull();
        verify(attemptRepository).saveAndFlush(attempt);
        verify(sessionCleanup).clearAll(USER_ID);
        verify(claimPublisher).publish(USER_ID, EkycStatus.PENDING);
        verify(evidenceRepository).markDeletedByObjectKeys(eq(List.of("k/front")), eq(NOW));
    }

    @Test
    void nothingWaitingForReviewIsConflict() {
        // Lượt mới nhất đang làm dở (chưa gửi duyệt): không có gì để rút.
        EkycAttempt inProgress = EkycTestData.attempt(101L, UUID.randomUUID(), PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        when(attemptRepository.findFirstByUserProfileIdOrderByCreatedAtDescIdDesc(PROFILE_ID))
                .thenReturn(Optional.of(inProgress));
        profile.setEkycStatus(EkycStatus.PENDING);

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("EKYC_NOT_WITHDRAWABLE");
                });
        assertThat(inProgress.getStatus()).isEqualTo(EkycAttemptStatus.IN_PROGRESS);
        verifyNoInteractions(claimPublisher, sessionCleanup);
    }

    @Test
    void noAttemptAtAllIsConflict() {
        when(attemptRepository.findFirstByUserProfileIdOrderByCreatedAtDescIdDesc(PROFILE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EKYC_NOT_WITHDRAWABLE"));
    }

    @Test
    void attemptAlreadyDecidedByStaffIsConflictWithFriendlyMessage() {
        attempt.approve(ADMIN_ID, null, NOW);
        profile.setEkycStatus(EkycStatus.VERIFIED);

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("ATTEMPT_ALREADY_DECIDED");
                    assertThat(e.getMessage()).startsWith("Hồ sơ vừa được nhân viên xử lý");
                });
        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.APPROVED);
        verifyNoInteractions(claimPublisher);
    }

    @Test
    void staffDecidingAtTheSameTimeWinsAndUserGetsConflict() {
        // Nhân viên commit trước: version của lượt đã tăng, flush của người dùng thất bại và rollback.
        when(attemptRepository.saveAndFlush(any(EkycAttempt.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(EkycAttempt.class, 100L));

        assertThatThrownBy(() -> service.withdraw(USER_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ATTEMPT_ALREADY_DECIDED"));
        verify(claimPublisher, never()).publish(any(), any());
        verify(sessionCleanup, never()).clearAll(any());
        verify(evidenceService, never()).deleteObjects(anyCollection());
    }

    @Test
    void storageDownStillWithdrawsAndLeavesObjectsForSweeper() {
        EkycEvidence front = EkycTestData.evidence(11L, 100L, EkycEvidenceKind.CCCD_FRONT, "k/front", EkycEvidenceStage.FINAL, NOW);
        when(evidenceRepository.findByAttemptIdInAndDeletedAtIsNull(List.of(100L))).thenReturn(List.of(front));
        when(evidenceService.deleteObjects(List.of("k/front"))).thenReturn(List.of());

        service.withdraw(USER_ID);

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.WITHDRAWN);
        verify(evidenceRepository, never()).markDeletedByObjectKeys(anyCollection(), any());
        verify(claimPublisher).publish(USER_ID, EkycStatus.PENDING);
    }
}
