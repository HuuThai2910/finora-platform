package com.finora.user.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.Gender;
import com.finora.common.exception.BusinessException;
import com.finora.user.client.AiEkycClient;
import com.finora.user.client.AiEkycClient.DocumentResult;
import com.finora.user.client.AiEkycClient.Portrait;
import com.finora.user.client.AiEkycClient.RetakeHint;
import com.finora.user.domain.CicMappingTask;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycConsent;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.integration.storage.EkycStorageException;
import com.finora.user.repository.CicMappingTaskRepository;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycAttemptService;
import com.finora.user.service.EkycClaimPublisher;
import com.finora.user.service.EkycConsentService;
import com.finora.user.service.EkycCounterStore;
import com.finora.user.service.EkycDraftStore;
import com.finora.user.service.EkycEvidenceService;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import com.finora.user.service.EkycIdentityService;
import com.finora.user.service.EkycRateLimitService;
import com.finora.user.service.EkycResultFile;
import com.finora.user.service.EkycSessionCleanup;
import com.finora.user.support.EkycTestData;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.finora.user.support.EkycTestData.ID_NUMBER;
import static com.finora.user.support.EkycTestData.JPEG_BASE64;
import static com.finora.user.support.EkycTestData.hash;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra bước quét thẻ (mọi mã kết quả) và bước xác nhận của eKYC EKYC-002. */
@ExtendWith(MockitoExtension.class)
class EkycVerificationServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long PROFILE_ID = 7L;
    /** 10:00 sáng 16/10/2026 giờ Việt Nam. */
    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private EkycAttemptRepository attemptRepository;
    @Mock private EkycEvidenceRepository evidenceRepository;
    @Mock private CicMappingTaskRepository cicMappingTaskRepository;
    @Mock private AiEkycClient aiEkycClient;
    @Mock private EkycRateLimitService rateLimitService;
    @Mock private EkycCounterStore counterStore;
    @Mock private EkycDraftStore draftStore;
    @Mock private EkycConsentService consentService;
    @Mock private EkycEvidenceService evidenceService;
    @Mock private EkycSessionCleanup sessionCleanup;
    @Mock private EkycClaimPublisher claimPublisher;

    private final ObjectMapper objectMapper = EkycTestData.objectMapper();
    private UserProfile profile;
    private EkycConsent consent;

    @BeforeEach
    void setUp() {
        profile = UserProfile.builder()
                .id(PROFILE_ID)
                .keycloakUserId(USER_ID)
                .email("nguoidung@example.test")
                .ekycStatus(EkycStatus.PENDING)
                .build();
        consent = EkycConsent.accept(PROFILE_ID, "ekyc-2026-10", List.of("ID_DOCUMENT"), "a".repeat(64), "ios", "1.0", NOW);
        ReflectionTestUtils.setField(consent, "id", 5L);
        lenient().when(userProfileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(userProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
    }

    private EkycVerificationServiceImpl service(Clock clock) {
        EkycIdentityService identityService = new EkycIdentityServiceImpl(userProfileRepository, EkycTestData.cryptoProperties());
        EkycAttemptService attemptService = new EkycAttemptServiceImpl(attemptRepository, evidenceRepository,
                cicMappingTaskRepository, identityService, objectMapper);
        return new EkycVerificationServiceImpl(userProfileRepository, attemptRepository, evidenceRepository, aiEkycClient,
                rateLimitService, counterStore, draftStore, consentService, evidenceService,
                attemptService, identityService, new EkycResultFileImpl(objectMapper), sessionCleanup, claimPublisher,
                EkycTestData.transactionTemplate(), clock);
    }

    private EkycVerificationServiceImpl service() {
        return service(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EkycVerifyRequest request() {
        return new EkycVerifyRequest(JPEG_BASE64, "data:image/jpeg;base64," + JPEG_BASE64, null, null,
                new EkycVerifyRequest.QrPayload("001095012345|...", "FRONT", "LIVE_SCAN"), "ekyc-2026-10");
    }

    // ── Bước quét thẻ ───────────────────────────────────────────────

    @Nested
    class Verify {

        private void givenReadyForAi() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent));
            when(evidenceService.isEnabled()).thenReturn(true);
            when(rateLimitService.tryAcquireVerifySlot(USER_ID)).thenReturn(true);
        }

        private void givenAiReturns(DocumentResult result) {
            when(aiEkycClient.analyzeDocument(any())).thenReturn(result);
        }

        private final AtomicReference<EkycAttempt> savedAttempt = new AtomicReference<>();

        private void givenAttemptPersistence() {
            when(attemptRepository.findByUserProfileIdAndStatusIn(eq(PROFILE_ID), anyCollection())).thenReturn(List.of());
            when(attemptRepository.save(any(EkycAttempt.class))).thenAnswer(invocation -> {
                EkycAttempt attempt = invocation.getArgument(0);
                ReflectionTestUtils.setField(attempt, "id", 100L);
                savedAttempt.set(attempt);
                return attempt;
            });
            lenient().when(attemptRepository.findById(100L)).thenAnswer(invocation -> Optional.ofNullable(savedAttempt.get()));
        }

        @Test
        void verifiedProfileReturnsVerifiedWithoutCallingAi() {
            profile.setEkycStatus(EkycStatus.VERIFIED);

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
            assertThat(result.status()).isEqualTo(EkycStatus.VERIFIED);
            verify(aiEkycClient, never()).analyzeDocument(any());
        }

        @Test
        void profileInManualReviewReturnsReviewPending() {
            profile.setEkycStatus(EkycStatus.MANUAL_REVIEW);

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.REVIEW_PENDING);
            assertThat(result.reviewRequired()).isTrue();
            verify(rateLimitService, never()).tryAcquireVerifySlot(any());
        }

        @Test
        void missingConsentReturnsConsentRequiredBeforeRateLimit() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.empty());

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.CONSENT_REQUIRED);
            verify(rateLimitService, never()).tryAcquireVerifySlot(any());
        }

        @Test
        void disabledStorageReturnsStorageUnavailableWithoutAi() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent));
            when(evidenceService.isEnabled()).thenReturn(false);

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.STORAGE_UNAVAILABLE);
            verify(rateLimitService, never()).tryAcquireVerifySlot(any());
            verify(aiEkycClient, never()).analyzeDocument(any());
        }

        @Test
        void notAnImageIsRejectedBeforeConsumingTheRateLimitSlot() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent));
            when(evidenceService.isEnabled()).thenReturn(true);
            String gif = java.util.Base64.getEncoder().encodeToString("GIF89a....".getBytes(StandardCharsets.US_ASCII));

            assertThatThrownBy(() -> service().verify(USER_ID, new EkycVerifyRequest(gif, JPEG_BASE64)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("INVALID_IMAGE");
            verify(rateLimitService, never()).tryAcquireVerifySlot(any());
        }

        @Test
        void rateLimitedCallDoesNotReachAi() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent));
            when(evidenceService.isEnabled()).thenReturn(true);
            when(rateLimitService.tryAcquireVerifySlot(USER_ID)).thenReturn(false);

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.RATE_LIMITED);
            verify(aiEkycClient, never()).analyzeDocument(any());
        }

        @Test
        void aiConnectionErrorReturnsAiUnavailableAndGivesBackTheSlot() {
            givenReadyForAi();
            when(aiEkycClient.analyzeDocument(any())).thenThrow(new RuntimeException("connection refused"));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.AI_UNAVAILABLE);
            assertThat(result.status()).isEqualTo(EkycStatus.PENDING);
            verify(rateLimitService).releaseVerifySlot(USER_ID);
        }

        @Test
        void aiBusy503ReturnsAiUnavailableAndGivesBackTheSlot() {
            givenReadyForAi();
            when(aiEkycClient.analyzeDocument(any())).thenThrow(feignError(503));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.AI_UNAVAILABLE);
            verify(rateLimitService).releaseVerifySlot(USER_ID);
        }

        @Test
        void aiRejectingTheImageIsOcrFailedAndKeepsTheSlot() {
            givenReadyForAi();
            when(aiEkycClient.analyzeDocument(any())).thenThrow(feignError(422));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.OCR_FAILED);
            verify(rateLimitService, never()).releaseVerifySlot(any());
        }

        @Test
        void retakeIncrementsCounterAndReturnsSideReasonAndMessage() {
            givenReadyForAi();
            when(counterStore.currentRetakeCount(USER_ID)).thenReturn(1);
            givenAiReturns(EkycTestData.retakeDocument(List.of(new RetakeHint("BACK", "MRZ_UNREADABLE"))));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.RETAKE);
            assertThat(result.retake()).singleElement().satisfies(item -> {
                assertThat(item.side()).isEqualTo("BACK");
                assertThat(item.reason()).isEqualTo("MRZ_UNREADABLE");
                assertThat(item.message()).contains("3 dòng ký tự");
            });
            assertThat(result.message()).isEqualTo(result.retake().get(0).message());
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.NONE);
            ArgumentCaptor<AiEkycClient.DocumentRequest> sent = ArgumentCaptor.forClass(AiEkycClient.DocumentRequest.class);
            verify(aiEkycClient).analyzeDocument(sent.capture());
            assertThat(sent.getValue().retakeCount()).isEqualTo(1);
            assertThat(sent.getValue().qrSide()).isEqualTo("FRONT");
            assertThat(sent.getValue().frontSource()).isEqualTo("IN_APP_CAMERA");
            verify(counterStore).incrementRetakeCount(USER_ID);
            verify(draftStore, never()).save(any(), any());
        }

        @Test
        void missingPortraitEmbeddingForcesFrontRetake() {
            givenReadyForAi();
            DocumentResult ok = EkycTestData.okDocument();
            givenAiReturns(new DocumentResult(ok.decision(), ok.cardType(), ok.reasons(), ok.warnings(), ok.retake(),
                    ok.fields(), ok.checks(), ok.quality(), new Portrait(false, null, null, null), false,
                    ok.thresholdsVersion()));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.RETAKE);
            assertThat(result.retake()).singleElement().satisfies(item -> {
                assertThat(item.side()).isEqualTo("FRONT");
                assertThat(item.reason()).isEqualTo("PORTRAIT_UNUSABLE");
            });
        }

        @Test
        void cmndIsUnsupported() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.document("NEEDS_REVIEW", "CMND", "123456789", "15/03/1995", null, null));

            assertThat(service().verify(USER_ID, request()).resultCode()).isEqualTo(EkycResultCode.CARD_UNSUPPORTED);
        }

        @Test
        void unknownCardWithoutNumberIsUnsupported() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.document("NEEDS_REVIEW", "UNKNOWN", null, null, null, null));

            assertThat(service().verify(USER_ID, request()).resultCode()).isEqualTo(EkycResultCode.CARD_UNSUPPORTED);
        }

        @Test
        void cardExpiredYesterdayInVietnamIsRejected() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.document("AUTO_OK", "CCCD_CHIP_2021", ID_NUMBER, "15/03/1995", "15/10/2026", false));

            assertThat(service().verify(USER_ID, request()).resultCode()).isEqualTo(EkycResultCode.CARD_EXPIRED);
        }

        @Test
        void expiryUsesVietnamDateNotUtcDate() {
            // 00:30 ngày 16/10 giờ Việt Nam nhưng vẫn là 15/10 theo UTC: thẻ hết hạn 15/10 đã hết hạn.
            givenReadyForAi();
            givenAiReturns(EkycTestData.document("AUTO_OK", "CCCD_CHIP_2021", ID_NUMBER, "15/03/1995", "15/10/2026", false));

            EkycResultResponse result = service(Clock.fixed(Instant.parse("2026-10-15T17:30:00Z"), ZoneOffset.UTC))
                    .verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.CARD_EXPIRED);
        }

        @Test
        void underageIsRejected() {
            givenReadyForAi();
            // Tròn 18 tuổi vào ngày mai (17/10/2026).
            givenAiReturns(EkycTestData.document("AUTO_OK", "CAN_CUOC_2024", "001208012345", "17/10/2008", "17/10/2033", false));

            assertThat(service().verify(USER_ID, request()).resultCode()).isEqualTo(EkycResultCode.UNDERAGE);
        }

        @Test
        void numberDifferentFromProfileIsIdMismatch() {
            profile.setIdNumberHash(hash(EkycTestData.OTHER_ID_NUMBER));
            givenReadyForAi();
            givenAiReturns(EkycTestData.okDocument());

            assertThat(service().verify(USER_ID, request()).resultCode()).isEqualTo(EkycResultCode.ID_MISMATCH);
        }

        @Test
        void numberOwnedByAnotherAccountIsIdTaken() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.okDocument());
            when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(true);

            assertThat(service().verify(USER_ID, request()).resultCode()).isEqualTo(EkycResultCode.ID_TAKEN);
            verify(attemptRepository, never()).save(any());
        }

        @Test
        void successOpensAttemptStoresImagesAndReturnsDraftWithSources() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.document("NEEDS_REVIEW", "CCCD_CHIP_2021", ID_NUMBER, "15/03/1995", null, true));
            givenAttemptPersistence();
            when(evidenceService.storeCardImages(eq(PROFILE_ID), any(), any(), any())).thenReturn(List.of(
                    stored(EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/a/cccd-front.jpg"),
                    stored(EkycEvidenceKind.CCCD_BACK, "ekyc/dev/7/a/cccd-back.jpg")));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.DRAFT_READY);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.LIVENESS);
            assertThat(result.reviewRequired()).as("NEEDS_REVIEW phải báo trước sẽ duyệt tay").isTrue();
            assertThat(result.cardType()).isEqualTo("CCCD_CHIP_2021");
            assertThat(result.ocrWarnings()).contains("ID_PROVINCE_UNKNOWN");
            assertThat(result.draft().idNumber().value()).isEqualTo(ID_NUMBER);
            assertThat(result.draft().idNumber().sources()).containsExactly("QR", "MRZ");
            assertThat(result.draft().idNumber().editable()).isFalse();
            assertThat(result.draft().fullName().editable()).as("tên từ QR không được sửa").isFalse();
            assertThat(result.draft().address().editable()).as("địa chỉ chỉ AI đọc được sửa").isTrue();
            assertThat(result.draft().placeOfOrigin()).as("trường không thu thập trả null").isNull();
            assertThat(result.draft().expiryDate().value()).isEqualTo(EkycVerificationServiceImpl.NO_EXPIRY_TEXT);

            ArgumentCaptor<EkycAttempt> attempt = ArgumentCaptor.forClass(EkycAttempt.class);
            verify(attemptRepository).save(attempt.capture());
            assertThat(attempt.getValue().getStatus()).isEqualTo(EkycAttemptStatus.IN_PROGRESS);
            assertThat(attempt.getValue().getConsentId()).isEqualTo(5L);
            assertThat(attempt.getValue().getDocumentReasons()).contains("QR_MRZ_AGREE", "ID_PROVINCE_UNKNOWN");
            verify(evidenceRepository).saveAll(anyList());

            ArgumentCaptor<EkycIdentityDraft> draft = ArgumentCaptor.forClass(EkycIdentityDraft.class);
            verify(draftStore).save(eq(USER_ID), draft.capture());
            assertThat(draft.getValue().attemptId()).isEqualTo(attempt.getValue().getPublicId());
            assertThat(draft.getValue().portraitEmbeddingB64()).isEqualTo(EkycTestData.EMBEDDING);
            assertThat(draft.getValue().noExpiry()).isTrue();
            verify(counterStore).resetRetakeCount(USER_ID);
            // Hồ sơ chưa được ghi ở bước quét.
            verify(userProfileRepository, never()).save(any());
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
        }

        @Test
        void storageFailureAbandonsTheNewAttempt() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.okDocument());
            givenAttemptPersistence();
            when(evidenceService.storeCardImages(anyLong(), any(), any(), any()))
                    .thenThrow(new EkycStorageException("PutObject thất bại"));

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.STORAGE_UNAVAILABLE);
            assertThat(savedAttempt.get().getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
            verify(evidenceRepository, never()).saveAll(anyList());
            verify(draftStore, never()).save(any(), any());
            verify(counterStore, never()).resetRetakeCount(any());
        }

        @Test
        void rescanAbandonsPreviousOpenAttemptAndDeletesItsObjects() {
            givenReadyForAi();
            givenAiReturns(EkycTestData.okDocument());
            EkycAttempt previous = EkycTestData.attempt(50L, UUID.randomUUID(), PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
            when(attemptRepository.findByUserProfileIdAndStatusIn(eq(PROFILE_ID), anyCollection())).thenReturn(List.of(previous));
            when(evidenceRepository.findByAttemptIdInAndDeletedAtIsNull(List.of(50L))).thenReturn(List.of(
                    EkycTestData.evidence(1L, 50L, EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/old/cccd-front.jpg",
                            EkycEvidenceStage.PENDING, NOW)));
            when(attemptRepository.save(any(EkycAttempt.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(evidenceService.deleteObjects(List.of("ekyc/dev/7/old/cccd-front.jpg")))
                    .thenReturn(List.of("ekyc/dev/7/old/cccd-front.jpg"));
            when(evidenceService.storeCardImages(anyLong(), any(), any(), any())).thenReturn(List.of());

            EkycResultResponse result = service().verify(USER_ID, request());

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.DRAFT_READY);
            assertThat(previous.getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
            verify(evidenceRepository).markDeletedByObjectKeys(List.of("ekyc/dev/7/old/cccd-front.jpg"), NOW);
        }
    }

    // ── Bước xác nhận ───────────────────────────────────────────────

    @Nested
    class Confirm {

        private final UUID attemptId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        private EkycAttempt attempt;

        @BeforeEach
        void setUpAttempt() {
            attempt = EkycTestData.attempt(100L, attemptId, PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        }

        private void givenDraft(EkycDocumentDecision decision, EkycLivenessResult liveness) {
            when(draftStore.find(USER_ID)).thenReturn(Optional.of(EkycTestData.draft(attemptId, decision, liveness)));
        }

        private void givenStoredEvidence() {
            when(attemptRepository.findByPublicId(attemptId)).thenReturn(Optional.of(attempt));
            when(attemptRepository.findById(100L)).thenReturn(Optional.of(attempt));
            EkycEvidence front = EkycTestData.evidence(1L, 100L, EkycEvidenceKind.CCCD_FRONT,
                    "ekyc/dev/7/" + attemptId + "/cccd-front.jpg", EkycEvidenceStage.PENDING, NOW);
            EkycEvidence video = EkycTestData.evidence(2L, 100L, EkycEvidenceKind.LIVENESS_VIDEO,
                    "ekyc/dev/7/" + attemptId + "/liveness-1.mp4", EkycEvidenceStage.PENDING, NOW);
            when(evidenceRepository.findByAttemptIdOrderByIdAsc(100L)).thenReturn(List.of(front, video));
            when(evidenceService.storeResult(eq(PROFILE_ID), eq(attemptId), any())).thenReturn(
                    stored(EkycEvidenceKind.RESULT, "ekyc/dev/7/" + attemptId + "/result.json"));
        }

        @Test
        void missingDraftIsDraftExpired() {
            when(draftStore.find(USER_ID)).thenReturn(Optional.empty());

            assertThat(service().confirm(USER_ID, null).resultCode()).isEqualTo(EkycResultCode.DRAFT_EXPIRED);
        }

        @Test
        void confirmBeforeLivenessIsLivenessRequired() {
            givenDraft(EkycDocumentDecision.AUTO_OK, null);

            EkycResultResponse result = service().confirm(USER_ID, null);

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_REQUIRED);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.LIVENESS);
        }

        @Test
        void failedLivenessIsLivenessRequired() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.FAIL);

            assertThat(service().confirm(USER_ID, null).resultCode()).isEqualTo(EkycResultCode.LIVENESS_REQUIRED);
        }

        @Test
        void editingADeterministicFieldIsNotAllowed() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);

            assertThatThrownBy(() -> service().confirm(USER_ID, Map.of("idNumber", "079203012345")))
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("EDIT_NOT_ALLOWED");
            assertThatThrownBy(() -> service().confirm(USER_ID, Map.of("fullName", "TÊN KHÁC")))
                    .as("tên từ QR không phải trường AI")
                    .isInstanceOf(BusinessException.class)
                    .extracting("code").isEqualTo("EDIT_NOT_ALLOWED");
            verify(userProfileRepository, never()).save(any());
        }

        @Test
        void automaticPathVerifiesProfileWritesIdentityAndFinalizesEvidence() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);
            givenStoredEvidence();
            when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.empty());
            Map<String, String> edits = new HashMap<>();
            edits.put("address", "  Số 1 Lê Lợi, Quận 1  ");

            EkycResultResponse result = service().confirm(USER_ID, edits);

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.VERIFIED);
            assertThat(result.status()).isEqualTo(EkycStatus.VERIFIED);
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.VERIFIED);
            assertThat(profile.isDocumentVerified()).isTrue();
            assertThat(profile.isLivenessVerified()).isTrue();
            assertThat(profile.getFaceMatchScore()).isEqualTo(0.52);
            assertThat(profile.getEkycCompletedAt()).isEqualTo(NOW);
            assertThat(profile.getIdNumberHash()).isEqualTo(hash(ID_NUMBER));
            assertThat(profile.getIdNumberEncrypted()).isEqualTo(ID_NUMBER);
            assertThat(profile.getFullName()).isEqualTo("NGUYỄN VĂN A");
            assertThat(profile.getDateOfBirth()).isEqualTo(LocalDate.of(1995, 3, 15));
            assertThat(profile.getGender()).isEqualTo(Gender.MALE);
            assertThat(profile.getAddress()).as("trường AI đã được người dùng sửa").isEqualTo("Số 1 Lê Lợi, Quận 1");
            assertThat(profile.getIdCardType()).isEqualTo("CCCD_CHIP_2021");
            assertThat(profile.getIdIssueDate()).isEqualTo(LocalDate.of(2021, 7, 1));
            assertThat(profile.getIdExpiryDate()).isEqualTo(LocalDate.of(2035, 3, 15));
            assertThat(profile.getBirthRegistrationPlace()).isEqualTo("UBND Phường 1");
            assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.VERIFIED);
            verify(cicMappingTaskRepository).save(any(CicMappingTask.class));
            verify(evidenceService).markFinal(List.of("ekyc/dev/7/" + attemptId + "/cccd-front.jpg",
                    "ekyc/dev/7/" + attemptId + "/liveness-1.mp4"));
            verify(evidenceRepository).saveAll(anyList());
            verify(sessionCleanup).clearAll(USER_ID);
            verify(claimPublisher).publish(USER_ID, EkycStatus.VERIFIED);
        }

        @Test
        void resultFileContainsNoFieldValues() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);
            givenStoredEvidence();
            when(cicMappingTaskRepository.findByUserProfileId(PROFILE_ID)).thenReturn(Optional.of(CicMappingTask.pending(PROFILE_ID, NOW)));
            ArgumentCaptor<byte[]> json = ArgumentCaptor.forClass(byte[].class);

            service().confirm(USER_ID, null);

            verify(evidenceService).storeResult(eq(PROFILE_ID), eq(attemptId), json.capture());
            String content = new String(json.getValue(), StandardCharsets.UTF_8);
            assertThat(content).contains("\"outcome\" : \"VERIFIED\"", "FACE_MATCH_HIGH", "cccd-front.jpg")
                    .doesNotContain(ID_NUMBER, "NGUYỄN", EkycTestData.EMBEDDING);
        }

        @Test
        void reviewPathSubmitsEncryptedDraftWithoutWritingIdentity() {
            givenDraft(EkycDocumentDecision.NEEDS_REVIEW, EkycLivenessResult.PASS);
            givenStoredEvidence();

            EkycResultResponse result = service().confirm(USER_ID, Map.of("birthRegistrationPlace", "UBND Phường 2"));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.REVIEW_PENDING);
            assertThat(result.status()).isEqualTo(EkycStatus.MANUAL_REVIEW);
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.MANUAL_REVIEW);
            assertThat(profile.getIdNumberHash()).as("chưa ghi định danh khi chờ duyệt").isNull();
            assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.MANUAL_REVIEW);
            assertThat(attempt.getSubmittedAt()).isEqualTo(NOW);
            assertThat(attempt.getDraftEncrypted()).contains(ID_NUMBER, "UBND Phường 2", "USER")
                    .doesNotContain(EkycTestData.EMBEDDING);
            verify(cicMappingTaskRepository, never()).save(any());
            verify(claimPublisher).publish(USER_ID, EkycStatus.MANUAL_REVIEW);
            verify(sessionCleanup).clearAll(USER_ID);
        }

        @Test
        void grayLivenessGoesToReviewEvenWithAutoOkDocument() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.REVIEW);
            givenStoredEvidence();

            assertThat(service().confirm(USER_ID, null).resultCode()).isEqualTo(EkycResultCode.REVIEW_PENDING);
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.MANUAL_REVIEW);
        }

        @Test
        void numberTakenWhileDraftWasOpenIsIdTakenAndAttemptAbandoned() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);
            when(userProfileRepository.existsByIdNumberHash(hash(ID_NUMBER))).thenReturn(true);
            when(attemptRepository.findByPublicId(attemptId)).thenReturn(Optional.of(attempt));
            when(attemptRepository.findById(100L)).thenReturn(Optional.of(attempt));
            when(evidenceRepository.findByAttemptIdOrderByIdAsc(100L)).thenReturn(List.of());

            EkycResultResponse result = service().confirm(USER_ID, null);

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.ID_TAKEN);
            assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
            verify(sessionCleanup).clearAll(USER_ID);
            verify(evidenceService, never()).storeResult(anyLong(), any(), any());
        }

        @Test
        void resultFileFailureKeepsEverythingUnchanged() {
            givenDraft(EkycDocumentDecision.AUTO_OK, EkycLivenessResult.PASS);
            when(attemptRepository.findByPublicId(attemptId)).thenReturn(Optional.of(attempt));
            when(evidenceRepository.findByAttemptIdOrderByIdAsc(100L)).thenReturn(List.of());
            when(evidenceService.storeResult(anyLong(), any(), any())).thenThrow(new EkycStorageException("down"));

            EkycResultResponse result = service().confirm(USER_ID, null);

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.STORAGE_UNAVAILABLE);
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
            assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.IN_PROGRESS);
            verify(sessionCleanup, never()).clearAll(any());
            verify(claimPublisher, never()).publish(any(), any());
        }

        @Test
        void alreadyVerifiedProfileShortCircuits() {
            profile.setEkycStatus(EkycStatus.VERIFIED);

            assertThat(service().confirm(USER_ID, null).resultCode()).isEqualTo(EkycResultCode.VERIFIED);
            verify(draftStore, never()).find(any());
        }
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static StoredEvidence stored(EkycEvidenceKind kind, String key) {
        return new StoredEvidence(kind, null, key, "d".repeat(64), 10L,
                kind == EkycEvidenceKind.RESULT ? "application/json" : "image/jpeg",
                kind == EkycEvidenceKind.RESULT ? EkycEvidenceStage.FINAL : EkycEvidenceStage.PENDING);
    }

    private static FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://ai/api/v1/ai/ekyc/document",
                Map.<String, Collection<String>>of(), new byte[0], StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("analyzeDocument", feign.Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of())
                .body(new byte[0])
                .build());
    }
}
