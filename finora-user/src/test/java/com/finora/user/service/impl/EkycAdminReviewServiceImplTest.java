package com.finora.user.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.dto.PageResponse;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.Gender;
import com.finora.common.exception.BusinessException;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.CicMappingTask;
import com.finora.user.domain.entity.EkycAttempt;
import com.finora.user.domain.entity.EkycConsent;
import com.finora.user.domain.entity.EkycEvidence;
import com.finora.user.domain.entity.EkycEvidenceAccessLog;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.domain.enums.EkycAttemptStatus;
import com.finora.user.domain.enums.EkycDocumentDecision;
import com.finora.user.domain.enums.EkycEvidenceKind;
import com.finora.user.domain.enums.EkycEvidenceStage;
import com.finora.user.domain.enums.EkycLivenessResult;
import com.finora.user.domain.model.EkycIdentityDraft;
import com.finora.user.dto.request.EkycApproveRequest;
import com.finora.user.dto.response.EkycAttemptDetailResponse;
import com.finora.user.dto.response.EkycAttemptSummaryResponse;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.integration.storage.StoredObject;
import com.finora.user.repository.CicMappingTaskRepository;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycAttemptSummaryRow;
import com.finora.user.repository.EkycConsentRepository;
import com.finora.user.repository.EkycEvidenceAccessLogRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycIdentityService;
import com.finora.user.util.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra trang Duyệt eKYC: danh sách, chi tiết có nhật ký, tải bằng chứng, duyệt/từ chối, xung đột. */
@ExtendWith(MockitoExtension.class)
class EkycAdminReviewServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long PROFILE_ID = 7L;
    private static final UUID ATTEMPT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock private EkycAttemptRepository attemptRepository;
    @Mock private EkycEvidenceRepository evidenceRepository;
    @Mock private EkycEvidenceAccessLogRepository accessLogRepository;
    @Mock private EkycConsentRepository consentRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private CicMappingTaskRepository cicMappingTaskRepository;
    @Mock private EkycEvidenceService evidenceService;
    @Mock private EkycClaimPublisher claimPublisher;

    private final ObjectMapper objectMapper = EkycTestData.objectMapper();
    private EkycAttemptService attemptService;
    private EkycAdminReviewServiceImpl service;
    private UserProfile profile;
    private EkycAttempt attempt;

    @BeforeEach
    void setUp() {
        EkycIdentityService identityService = new EkycIdentityServiceImpl(userProfileRepository, EkycTestData.cryptoProperties());
        attemptService = new EkycAttemptServiceImpl(attemptRepository, evidenceRepository, cicMappingTaskRepository,
                identityService, objectMapper);
        service = new EkycAdminReviewServiceImpl(attemptRepository, evidenceRepository, accessLogRepository,
                consentRepository, userProfileRepository, attemptService, identityService, evidenceService,
                claimPublisher, EkycTestData.transactionTemplate(), Clock.fixed(NOW, ZoneOffset.UTC));
        profile = UserProfile.builder().id(PROFILE_ID).keycloakUserId(USER_ID).email("nguoidung@example.test")
                .ekycStatus(EkycStatus.MANUAL_REVIEW).build();
        attempt = EkycTestData.attempt(100L, ATTEMPT_ID, PROFILE_ID, EkycDocumentDecision.NEEDS_REVIEW, NOW);
        attempt.recordLiveness(EkycLivenessResult.REVIEW, "[\"ACTIONS_MATCH\",\"FACE_MATCH_GRAY\"]", 0.41,
                "doc,liveness", NOW);
        attempt.submitForReview(attemptService.draftJson(
                EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.NEEDS_REVIEW, EkycLivenessResult.REVIEW)
                        .withoutBiometrics()), NOW);
        lenient().when(attemptRepository.findByPublicId(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        lenient().when(userProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        lenient().when(evidenceRepository.findByAttemptIdOrderByIdAsc(100L)).thenReturn(List.of());
    }

    @Test
    void listDefaultsToManualReviewQueueAndClampsPageSize() {
        when(attemptRepository.findSummariesByStatus(eq(EkycAttemptStatus.MANUAL_REVIEW), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(new EkycAttemptSummaryRow(attempt, "nguoidung@example.test")),
                        invocation.getArgument(1), 1));

        PageResponse<EkycAttemptSummaryResponse> page = service.list(null, 0, 500);

        ArgumentCaptor<PageRequest> pageable = ArgumentCaptor.forClass(PageRequest.class);
        verify(attemptRepository).findSummariesByStatus(eq(EkycAttemptStatus.MANUAL_REVIEW), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.attemptId()).isEqualTo(ATTEMPT_ID);
            assertThat(summary.email()).isEqualTo("nguoidung@example.test");
            assertThat(summary.status()).isEqualTo("MANUAL_REVIEW");
            assertThat(summary.livenessResult()).isEqualTo("REVIEW");
            assertThat(summary.faceMatchZone()).isEqualTo("GRAY");
            assertThat(summary.reasons()).containsExactly("QR_MRZ_AGREE");
        });
    }

    @Test
    void listAllAndInvalidStatus() {
        when(attemptRepository.findAllSummaries(any())).thenReturn(new PageImpl<>(List.of()));

        assertThat(service.list("all", 0, 20).getContent()).isEmpty();
        assertThatThrownBy(() -> service.list("DONE", 0, 20))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_STATUS"));
    }

    @Test
    void detailDecryptsDraftAndLogsAccess() {
        EkycConsent consent = EkycConsent.accept(PROFILE_ID, "ekyc-2026-10", List.of("ID_DOCUMENT"), "a".repeat(64),
                "ios", "1", NOW);
        when(consentRepository.findById(5L)).thenReturn(Optional.of(consent));

        EkycAttemptDetailResponse detail = service.detail(ATTEMPT_ID.toString(), ADMIN_ID);
        assertThat(detail.reviewerEmail()).as("lượt chưa có người duyệt thì không tra hồ sơ").isNull();
        verify(userProfileRepository, never()).findByKeycloakUserId(any());

        assertThat(detail.draft().idNumber().value()).isEqualTo(EkycTestData.ID_NUMBER);
        assertThat(detail.draft().placeOfOrigin()).isNull();
        assertThat(detail.livenessReasons()).containsExactly("ACTIONS_MATCH", "FACE_MATCH_GRAY");
        assertThat(detail.faceMatchZone()).isEqualTo("GRAY");
        assertThat(detail.consentVersion()).isEqualTo("ekyc-2026-10");
        ArgumentCaptor<EkycEvidenceAccessLog> log = ArgumentCaptor.forClass(EkycEvidenceAccessLog.class);
        verify(accessLogRepository).save(log.capture());
        assertThat(log.getValue().getEvidenceId()).isNull();
        assertThat(log.getValue().getAdminKeycloakId()).isEqualTo(ADMIN_ID);
    }

    @Test
    void unknownOrMalformedAttemptIsNotFound() {
        assertThatThrownBy(() -> service.detail("not-a-uuid", ADMIN_ID)).isInstanceOf(ResourceNotFoundException.class);
        UUID other = UUID.randomUUID();
        when(attemptRepository.findByPublicId(other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.detail(other.toString(), ADMIN_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void openEvidenceLogsAccessAndStreams() {
        EkycEvidence evidence = EkycTestData.evidence(11L, 100L, EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/a/cccd-front.jpg",
                EkycEvidenceStage.FINAL, NOW);
        when(evidenceRepository.findByIdAndAttemptId(11L, 100L)).thenReturn(Optional.of(evidence));
        when(evidenceService.open("ekyc/dev/7/a/cccd-front.jpg"))
                .thenReturn(new StoredObject(new ByteArrayInputStream(EkycTestData.JPEG_BYTES), 12, "image/jpeg"));

        EkycAdminReviewServiceImpl.EvidenceDownload download = service.openEvidence(ATTEMPT_ID.toString(), "11", ADMIN_ID);

        assertThat(download.contentType()).isEqualTo("image/jpeg");
        assertThat(download.contentLength()).isEqualTo(12);
        ArgumentCaptor<EkycEvidenceAccessLog> log = ArgumentCaptor.forClass(EkycEvidenceAccessLog.class);
        verify(accessLogRepository).save(log.capture());
        assertThat(log.getValue().getEvidenceId()).isEqualTo(11L);
    }

    @Test
    void deletedEvidenceIsGone() {
        EkycEvidence evidence = EkycTestData.evidence(11L, 100L, EkycEvidenceKind.CCCD_FRONT, "k", EkycEvidenceStage.FINAL, NOW);
        evidence.markDeleted(NOW);
        when(evidenceRepository.findByIdAndAttemptId(11L, 100L)).thenReturn(Optional.of(evidence));

        assertThatThrownBy(() -> service.openEvidence(ATTEMPT_ID.toString(), "11", ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                    assertThat(e.getCode()).isEqualTo("EVIDENCE_DELETED");
                });
        verify(accessLogRepository, never()).save(any());
    }

    @Test
    void objectMissingInStorageIsGone() {
        EkycEvidence evidence = EkycTestData.evidence(11L, 100L, EkycEvidenceKind.CCCD_FRONT, "k", EkycEvidenceStage.FINAL, NOW);
        when(evidenceRepository.findByIdAndAttemptId(11L, 100L)).thenReturn(Optional.of(evidence));
        when(evidenceService.open("k")).thenThrow(new EkycStorageException("GetObject", true, null));

        assertThatThrownBy(() -> service.openEvidence(ATTEMPT_ID.toString(), "11", ADMIN_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EVIDENCE_DELETED"));
    }

    @Test
    void approveWritesIdentityVerifiesProfileAndPublishesClaim() {
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        UserProfile admin = UserProfile.builder().id(99L).keycloakUserId(ADMIN_ID).email("admin@example.test")
                .fullName("Trần Quản Trị").build();
        when(userProfileRepository.findByKeycloakUserId(ADMIN_ID)).thenReturn(Optional.of(admin));

        EkycAttemptDetailResponse detail = service.approve(ATTEMPT_ID.toString(), ADMIN_ID, "  khớp ảnh  ");

        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.APPROVED);
        assertThat(attempt.getReviewerKeycloakId()).isEqualTo(ADMIN_ID);
        assertThat(attempt.getReviewNote()).isEqualTo("khớp ảnh");
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.isLivenessVerified()).as("liveness REVIEW được người duyệt chấp nhận").isTrue();
        assertThat(profile.getFaceMatchScore()).isEqualTo(0.41);
        assertThat(profile.getIdNumberHash()).isEqualTo(EkycTestData.hash(EkycTestData.ID_NUMBER));
        assertThat(detail.status()).isEqualTo("APPROVED");
        assertThat(detail.reviewedAt()).isEqualTo(NOW);
        assertThat(detail.reviewerKeycloakId()).isEqualTo(ADMIN_ID);
        assertThat(detail.reviewerEmail()).isEqualTo("admin@example.test");
        assertThat(detail.reviewerName()).isEqualTo("Trần Quản Trị");
        verify(cicMappingTaskRepository).save(any(CicMappingTask.class));
        verify(attemptRepository).saveAndFlush(attempt);
        verify(claimPublisher).publish(USER_ID, EkycStatus.VERIFIED);
    }

    @Test
    void approveWorksForAttemptEscalatedAfterThreeLivenessFailures() {
        EkycAttempt failed = EkycTestData.attempt(101L, UUID.randomUUID(), PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        failed.recordLiveness(EkycLivenessResult.FAIL, "[\"FACE_MISMATCH\"]", 0.1, "doc,liveness", NOW);
        failed.submitForReview(attemptService.draftJson(EkycTestData.draft(failed.getPublicId(),
                EkycDocumentDecision.AUTO_OK, EkycLivenessResult.FAIL).withoutBiometrics()), NOW);
        when(attemptRepository.findByPublicId(failed.getPublicId())).thenReturn(Optional.of(failed));
        when(evidenceRepository.findByAttemptIdOrderByIdAsc(101L)).thenReturn(List.of());
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        EkycAttemptDetailResponse detail = service.approve(failed.getPublicId().toString(), ADMIN_ID, null);

        assertThat(detail.status()).isEqualTo("APPROVED");
        assertThat(detail.faceMatchZone()).isEqualTo("LOW");
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.isLivenessVerified()).as("liveness FAIL không được tính là đã qua").isFalse();
    }

    @Test
    void decidingAnAttemptThatIsNotWaitingIsConflict() {
        attempt.approve(ADMIN_ID, null, NOW);

        assertThatThrownBy(() -> service.reject(ATTEMPT_ID.toString(), ADMIN_ID, "sai"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("ATTEMPT_ALREADY_DECIDED");
                });
        verify(claimPublisher, never()).publish(any(), any());
    }

    @Test
    void concurrentDecisionLosesWithConflict() {
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(attemptRepository.saveAndFlush(any(EkycAttempt.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(EkycAttempt.class, 100L));

        assertThatThrownBy(() -> service.approve(ATTEMPT_ID.toString(), ADMIN_ID, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ATTEMPT_ALREADY_DECIDED"));
        verify(claimPublisher, never()).publish(any(), any());
    }

    @Test
    void approveRefusesNumberAlreadyTaken() {
        when(userProfileRepository.existsByIdNumberHash(EkycTestData.hash(EkycTestData.ID_NUMBER))).thenReturn(true);

        assertThatThrownBy(() -> service.approve(ATTEMPT_ID.toString(), ADMIN_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("ID_TAKEN"));
        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.MANUAL_REVIEW);
    }

    @Test
    void rejectFailsProfileAndRequiresNote() {
        assertThatThrownBy(() -> service.reject(ATTEMPT_ID.toString(), ADMIN_ID, "  "))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REVIEW_NOTE_REQUIRED"));

        EkycAttemptDetailResponse detail = service.reject(ATTEMPT_ID.toString(), ADMIN_ID, "Ảnh thẻ bị che số");

        assertThat(detail.status()).isEqualTo("REJECTED");
        assertThat(detail.reviewNote()).isEqualTo("Ảnh thẻ bị che số");
        assertThat(detail.reviewerKeycloakId()).isEqualTo(ADMIN_ID);
        assertThat(detail.reviewerEmail()).as("admin tạo thẳng trên Keycloak, không có hồ sơ FINORA").isNull();
        assertThat(detail.reviewerName()).isNull();
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.FAILED);
        assertThat(profile.getEkycCompletedAt()).isEqualTo(NOW);
        verify(claimPublisher).publish(USER_ID, EkycStatus.FAILED);
    }

    // ── Hồ sơ chuyển duyệt tay vì AI gặp sự cố ──────────────────────

    /** Lượt AI lỗi ngay ở bước thẻ: bản nháp rỗng trường, không có kết quả liveness. */
    private EkycAttempt aiUnavailableAttempt(UUID publicId) {
        EkycAttempt aiAttempt = EkycAttempt.start(publicId, PROFILE_ID, 5L, null, EkycDocumentDecision.NEEDS_REVIEW,
                "[\"AI_UNAVAILABLE\"]", null, NOW);
        ReflectionTestUtils.setField(aiAttempt, "id", 102L);
        ReflectionTestUtils.setField(aiAttempt, "version", 0L);
        aiAttempt.recordLivenessWithoutAi("[\"AI_UNAVAILABLE\"]", NOW);
        aiAttempt.submitForReview(attemptService.draftJson(
                EkycIdentityDraft.withoutAiReading(publicId).withLivenessWithoutAi("VIDEO", 1)), NOW);
        when(attemptRepository.findByPublicId(publicId)).thenReturn(Optional.of(aiAttempt));
        lenient().when(evidenceRepository.findByAttemptIdOrderByIdAsc(102L)).thenReturn(List.of());
        return aiAttempt;
    }

    private static EkycApproveRequest.Identity staffIdentity(String dateOfBirth) {
        return new EkycApproveRequest.Identity("CAN_CUOC_2024", EkycTestData.ID_NUMBER, "NGUYỄN VĂN A", dateOfBirth,
                "Nam", "Phường 1, Quận 3, TP. Hồ Chí Minh", null, "UBND Phường 1", "01/07/2024", null, true);
    }

    @Test
    void aiUnavailableAttemptCannotBeApprovedWithoutIdentity() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt aiAttempt = aiUnavailableAttempt(publicId);

        assertThatThrownBy(() -> service.approve(publicId.toString(), ADMIN_ID, null, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("IDENTITY_REQUIRED");
                    assertThat(e.getMessage()).contains("Loại thẻ", "Số định danh", "Họ và tên");
                });
        assertThat(aiAttempt.getStatus()).isEqualTo(EkycAttemptStatus.MANUAL_REVIEW);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.MANUAL_REVIEW);
        verify(claimPublisher, never()).publish(any(), any());
    }

    @Test
    void aiUnavailableAttemptApprovedWithStaffIdentityWritesProfile() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt aiAttempt = aiUnavailableAttempt(publicId);
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        EkycAttemptDetailResponse detail = service.approve(publicId.toString(), ADMIN_ID, "đã so mặt",
                staffIdentity("15/03/1995"));

        assertThat(aiAttempt.getStatus()).isEqualTo(EkycAttemptStatus.APPROVED);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.getIdNumberHash()).isEqualTo(EkycTestData.hash(EkycTestData.ID_NUMBER));
        assertThat(profile.getFullName()).isEqualTo("NGUYỄN VĂN A");
        assertThat(profile.getGender()).isEqualTo(Gender.MALE);
        assertThat(profile.getIdCardType()).isEqualTo("CAN_CUOC_2024");
        assertThat(profile.getIdExpiryDate()).as("thẻ không thời hạn").isNull();
        assertThat(profile.isLivenessVerified()).as("nhân viên đã tự so mặt trên video").isTrue();
        assertThat(profile.getFaceMatchScore()).isNull();
        assertThat(detail.aiUnavailable()).isTrue();
        assertThat(detail.requiredIdentityFields()).as("đã duyệt thì không còn trường phải nhập").isEmpty();
        assertThat(detail.draft().idNumber().sources()).containsExactly("STAFF");
        assertThat(detail.draft().expiryDate().value()).isEqualTo("Không thời hạn");
        verify(claimPublisher).publish(USER_ID, EkycStatus.VERIFIED);
    }

    @Test
    void staffIdentityStillFollowsBusinessRules() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt aiAttempt = aiUnavailableAttempt(publicId);

        assertThatThrownBy(() -> service.approve(publicId.toString(), ADMIN_ID, null, staffIdentity("01/01/2010")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("UNDERAGE"));

        when(userProfileRepository.existsByIdNumberHash(EkycTestData.hash(EkycTestData.ID_NUMBER))).thenReturn(true);
        assertThatThrownBy(() -> service.approve(publicId.toString(), ADMIN_ID, null, staffIdentity("15/03/1995")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("ID_TAKEN");
                });
        assertThat(aiAttempt.getStatus()).isEqualTo(EkycAttemptStatus.MANUAL_REVIEW);
    }

    @Test
    void identityIsRefusedForOrdinaryManualReview() {
        assertThatThrownBy(() -> service.approve(ATTEMPT_ID.toString(), ADMIN_ID, null, staffIdentity("15/03/1995")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("IDENTITY_NOT_ALLOWED"));
        assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.MANUAL_REVIEW);
    }

    @Test
    void livenessOnlyOutageApprovesWithoutIdentityAndCountsStaffFaceCheck() {
        // AI đọc thẻ được, chỉ lỗi ở bước so mặt: bản nháp đủ trường, nhân viên chỉ cần so mặt.
        EkycAttempt faceOnly = EkycTestData.attempt(103L, UUID.randomUUID(), PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        faceOnly.recordLivenessWithoutAi("[\"AI_UNAVAILABLE\"]", NOW);
        faceOnly.submitForReview(attemptService.draftJson(EkycTestData.draft(faceOnly.getPublicId(),
                EkycDocumentDecision.AUTO_OK, null).withLivenessWithoutAi("VIDEO", 1).withoutBiometrics()), NOW);
        when(attemptRepository.findByPublicId(faceOnly.getPublicId())).thenReturn(Optional.of(faceOnly));
        when(evidenceRepository.findByAttemptIdOrderByIdAsc(103L)).thenReturn(List.of());
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        EkycAttemptDetailResponse before = service.detail(faceOnly.getPublicId().toString(), ADMIN_ID);
        assertThat(before.aiUnavailable()).isTrue();
        assertThat(before.requiredIdentityFields()).isEmpty();

        EkycAttemptDetailResponse detail = service.approve(faceOnly.getPublicId().toString(), ADMIN_ID, null, null);

        assertThat(detail.status()).isEqualTo("APPROVED");
        assertThat(profile.isLivenessVerified()).isTrue();
        assertThat(profile.getIdNumberHash()).isEqualTo(EkycTestData.hash(EkycTestData.ID_NUMBER));
    }

    @Test
    void detailAndListFlagAiOutageAndRequiredFields() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt aiAttempt = aiUnavailableAttempt(publicId);
        when(attemptRepository.findSummariesByStatus(eq(EkycAttemptStatus.MANUAL_REVIEW), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(new EkycAttemptSummaryRow(aiAttempt, "nguoidung@example.test"),
                        new EkycAttemptSummaryRow(attempt, "khac@example.test")), invocation.getArgument(1), 2));

        EkycAttemptDetailResponse detail = service.detail(publicId.toString(), ADMIN_ID);
        PageResponse<EkycAttemptSummaryResponse> page = service.list(null, 0, 20);

        assertThat(detail.aiUnavailable()).isTrue();
        assertThat(detail.requiredIdentityFields()).containsExactly(
                "cardType", "idNumber", "fullName", "dateOfBirth", "gender", "issueDate", "expiryDate");
        assertThat(detail.faceMatchScore()).isNull();
        assertThat(page.getContent()).extracting(EkycAttemptSummaryResponse::aiUnavailable).containsExactly(true, false);
    }

    @Test
    void withdrawnAttemptShowsStatusAndCannotBeDecided() {
        attempt.withdrawFromReview(NOW);
        profile.resetEkycAfterReviewWithdrawal();

        EkycAttemptDetailResponse detail = service.detail(ATTEMPT_ID.toString(), ADMIN_ID);
        assertThat(detail.status()).isEqualTo("WITHDRAWN");
        assertThat(detail.draft()).as("bản nháp bị xoá khi rút lại").isNull();

        assertThatThrownBy(() -> service.approve(ATTEMPT_ID.toString(), ADMIN_ID, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("ATTEMPT_WITHDRAWN");
                    assertThat(e.getMessage()).isEqualTo("Người dùng đã rút lại hồ sơ này.");
                });
        assertThatThrownBy(() -> service.reject(ATTEMPT_ID.toString(), ADMIN_ID, "sai"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ATTEMPT_WITHDRAWN"));
        verify(claimPublisher, never()).publish(any(), any());
    }

    // ── Hồ sơ người dùng báo thông tin đọc từ thẻ chưa đúng ─────────

    /** Lượt người dùng báo sai: đọc thẻ AUTO_OK, liveness đạt, đã gửi duyệt kèm mã USER_REPORTED_INCORRECT. */
    private EkycAttempt userReportedAttempt(UUID publicId) {
        EkycAttempt reported = EkycTestData.attempt(104L, publicId, PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        reported.recordLiveness(EkycLivenessResult.PASS, "[\"ACTIONS_MATCH\",\"FACE_MATCH_HIGH\"]", 0.52,
                "doc,liveness", NOW);
        reported.replaceDocumentReasons("[\"QR_MRZ_AGREE\",\"USER_REPORTED_INCORRECT\"]", NOW);
        reported.submitForReview(attemptService.draftJson(EkycTestData.draft(publicId, EkycDocumentDecision.AUTO_OK,
                EkycLivenessResult.PASS).withUserReportedIncorrect().withoutBiometrics()), NOW);
        when(attemptRepository.findByPublicId(publicId)).thenReturn(Optional.of(reported));
        lenient().when(evidenceRepository.findByAttemptIdOrderByIdAsc(104L)).thenReturn(List.of());
        return reported;
    }

    @Test
    void userReportedAttemptApprovedWithCorrectionsWritesProfile() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt reported = userReportedAttempt(publicId);
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        EkycApproveRequest.Identity corrections = new EkycApproveRequest.Identity(null, null, "NGUYỄN VĂN AN",
                null, null, "Số 9 Lê Lợi, Quận 1, TP. Hồ Chí Minh", null, null, null, null, null);

        EkycAttemptDetailResponse detail = service.approve(publicId.toString(), ADMIN_ID, "sửa họ tên", corrections);

        assertThat(reported.getStatus()).isEqualTo(EkycAttemptStatus.APPROVED);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(profile.getFullName()).isEqualTo("NGUYỄN VĂN AN");
        assertThat(profile.getAddress()).isEqualTo("Số 9 Lê Lợi, Quận 1, TP. Hồ Chí Minh");
        assertThat(profile.getIdNumberHash()).isEqualTo(EkycTestData.hash(EkycTestData.ID_NUMBER));
        assertThat(profile.isLivenessVerified()).as("giữ kết quả so mặt của AI").isTrue();
        assertThat(profile.getFaceMatchScore()).isEqualTo(0.52);
        assertThat(detail.userReportedIncorrect()).isTrue();
        assertThat(detail.draft().fullName().sources()).containsExactly("STAFF");
        assertThat(detail.draft().idNumber().sources()).containsExactly("QR", "MRZ");
        verify(claimPublisher).publish(USER_ID, EkycStatus.VERIFIED);
    }

    @Test
    void userReportedAttemptCanBeApprovedWithoutChanges() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt reported = userReportedAttempt(publicId);
        when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        service.approve(publicId.toString(), ADMIN_ID, "thông tin đúng như thẻ", null);

        assertThat(reported.getStatus()).isEqualTo(EkycAttemptStatus.APPROVED);
        assertThat(profile.getFullName()).isEqualTo("NGUYỄN VĂN A");
    }

    @Test
    void detailAndListFlagUserReportedIncorrect() {
        UUID publicId = UUID.randomUUID();
        EkycAttempt reported = userReportedAttempt(publicId);
        when(attemptRepository.findSummariesByStatus(eq(EkycAttemptStatus.MANUAL_REVIEW), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(new EkycAttemptSummaryRow(reported, "nguoidung@example.test"),
                        new EkycAttemptSummaryRow(attempt, "khac@example.test")), invocation.getArgument(1), 2));

        EkycAttemptDetailResponse detail = service.detail(publicId.toString(), ADMIN_ID);
        PageResponse<EkycAttemptSummaryResponse> page = service.list(null, 0, 20);

        assertThat(detail.userReportedIncorrect()).isTrue();
        assertThat(detail.aiUnavailable()).isFalse();
        assertThat(detail.requiredIdentityFields()).as("bản nháp đã đủ trường").isEmpty();
        assertThat(page.getContent()).extracting(EkycAttemptSummaryResponse::userReportedIncorrect)
                .containsExactly(true, false);
    }
}
