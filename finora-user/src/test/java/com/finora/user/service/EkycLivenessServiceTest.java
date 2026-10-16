package com.finora.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.user.client.AiLivenessClient;
import com.finora.user.client.AiLivenessClient.AiLivenessException;
import com.finora.user.client.AiLivenessClient.EvidenceFrame;
import com.finora.user.client.AiLivenessClient.FaceMatch;
import com.finora.user.client.AiLivenessClient.LivenessMedia;
import com.finora.user.client.AiLivenessClient.LivenessResult;
import com.finora.user.client.AiLivenessClient.LivenessSpec;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycConsent;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import com.finora.user.domain.LivenessAction;
import com.finora.user.domain.LivenessStep;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.LivenessChallengeResponse;
import com.finora.user.repository.CicMappingTaskRepository;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.EkycChallengeStore.StoredChallenge;
import com.finora.user.service.EkycEvidenceService.StoredEvidence;
import com.finora.user.service.EkycLivenessService.LivenessUpload;
import com.finora.user.service.EkycLivenessService.UploadedFile;
import com.finora.user.support.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra thử thách liveness và ánh xạ kết quả gửi video (EKYC-002 mục 3.4). */
@ExtendWith(MockitoExtension.class)
class EkycLivenessServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long PROFILE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private static final UUID ATTEMPT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID SESSION_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    /** MP4 tối thiểu: hộp ftyp ở byte 4–8, nhãn isom. */
    private static final byte[] MP4 = "\0\0\0\u0018ftypisom\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MOV = "\0\0\0\u0014ftypqt  \0\0\0\0".getBytes(StandardCharsets.ISO_8859_1);

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private EkycAttemptRepository attemptRepository;
    @Mock private EkycEvidenceRepository evidenceRepository;
    @Mock private CicMappingTaskRepository cicMappingTaskRepository;
    @Mock private EkycConsentService consentService;
    @Mock private EkycDraftStore draftStore;
    @Mock private EkycChallengeStore challengeStore;
    @Mock private EkycRateLimitService rateLimitService;
    @Mock private EkycCounterStore counterStore;
    @Mock private AiLivenessClient livenessClient;
    @Mock private EkycEvidenceService evidenceService;
    @Mock private EkycSessionCleanup sessionCleanup;
    @Mock private EkycClaimPublisher claimPublisher;
    @Mock private LivenessChallengeGenerator challengeGenerator;

    private final ObjectMapper objectMapper = EkycTestData.objectMapper();
    private UserProfile profile;
    private EkycAttempt attempt;
    private EkycLivenessService service;

    @BeforeEach
    void setUp() {
        profile = UserProfile.builder().id(PROFILE_ID).keycloakUserId(USER_ID).email("nguoidung@example.test")
                .ekycStatus(EkycStatus.PENDING).build();
        attempt = EkycTestData.attempt(100L, ATTEMPT_ID, PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        lenient().when(userProfileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(userProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        lenient().when(attemptRepository.findById(100L)).thenReturn(Optional.of(attempt));
        EkycIdentityService identityService = new EkycIdentityService(userProfileRepository, EkycTestData.cryptoProperties());
        EkycAttemptService attemptService = new EkycAttemptService(attemptRepository, evidenceRepository,
                cicMappingTaskRepository, identityService, objectMapper);
        service = new EkycLivenessService(userProfileRepository, attemptRepository, evidenceRepository, consentService,
                draftStore, challengeStore, rateLimitService, counterStore, livenessClient, evidenceService,
                attemptService, new EkycResultFile(objectMapper), sessionCleanup, claimPublisher, challengeGenerator,
                objectMapper, EkycTestData.transactionTemplate(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── Thử thách ───────────────────────────────────────────────────

    @Nested
    class Challenge {

        @Test
        void issuesChallengeBoundToDraftAttemptWithNinetySecondExpiry() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent()));
            when(draftStore.find(USER_ID)).thenReturn(Optional.of(EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null)));
            when(rateLimitService.tryAcquireChallengeSlot(USER_ID)).thenReturn(true);
            when(challengeGenerator.generate()).thenReturn(steps());

            LivenessChallengeResponse response = service.createChallenge(USER_ID);

            assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(90));
            assertThat(response.maxDurationSec()).isEqualTo(12);
            assertThat(response.steps()).hasSize(7);
            assertThat(response.steps().get(1)).isEqualTo(new LivenessChallengeResponse.Step("TURN_LEFT", 1300));
            assertThat(response.video()).isEqualTo(new LivenessChallengeResponse.VideoSettings("4:3", 1_500_000, true));
            ArgumentCaptor<StoredChallenge> stored = ArgumentCaptor.forClass(StoredChallenge.class);
            verify(challengeStore).save(eq(USER_ID), stored.capture());
            assertThat(stored.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
            assertThat(stored.getValue().sessionId()).isEqualTo(response.sessionId());
            assertThat(stored.getValue().issuedAtInstant()).isEqualTo(NOW);
        }

        @Test
        void noDraftIsConflictDraftExpired() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent()));
            when(draftStore.find(USER_ID)).thenReturn(Optional.empty());

            assertConflict(() -> service.createChallenge(USER_ID), HttpStatus.CONFLICT, "DRAFT_EXPIRED");
        }

        @Test
        void noConsentIsConflictConsentRequired() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.empty());

            assertConflict(() -> service.createChallenge(USER_ID), HttpStatus.CONFLICT, "CONSENT_REQUIRED");
        }

        @Test
        void manualReviewIsConflictReviewPending() {
            profile.setEkycStatus(EkycStatus.MANUAL_REVIEW);

            assertConflict(() -> service.createChallenge(USER_ID), HttpStatus.CONFLICT, "REVIEW_PENDING");
        }

        @Test
        void burstIsTooManyRequests() {
            when(consentService.findActiveConsent(PROFILE_ID)).thenReturn(Optional.of(consent()));
            when(draftStore.find(USER_ID)).thenReturn(Optional.of(EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null)));
            when(rateLimitService.tryAcquireChallengeSlot(USER_ID)).thenReturn(false);

            assertConflict(() -> service.createChallenge(USER_ID), HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
            verify(challengeStore, never()).save(any(), any());
        }
    }

    // ── Gửi video ───────────────────────────────────────────────────

    @Nested
    class Submit {

        private void givenValidSession() {
            when(rateLimitService.tryAcquireLivenessSlot(USER_ID)).thenReturn(true);
            when(challengeStore.consume(USER_ID)).thenReturn(Optional.of(
                    new StoredChallenge(SESSION_ID, steps(), ATTEMPT_ID, NOW.minusSeconds(20).toString())));
            when(draftStore.find(USER_ID)).thenReturn(Optional.of(EkycTestData.draft(ATTEMPT_ID, EkycDocumentDecision.AUTO_OK, null)));
            when(attemptRepository.findByPublicId(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
        }

        private void givenFreshMediaAndAi(LivenessResult result) {
            when(counterStore.registerMedia(anyString())).thenReturn(true);
            when(livenessClient.analyze(any(), any())).thenReturn(result);
            when(evidenceService.storeLiveness(anyLong(), eq(ATTEMPT_ID), anyInt(), any(), anyList(), any()))
                    .thenReturn(List.of(new StoredEvidence(EkycEvidenceKind.LIVENESS_VIDEO, null,
                            "ekyc/dev/7/" + ATTEMPT_ID + "/liveness-1.mp4", "e".repeat(64), MP4.length,
                            "video/mp4", EkycEvidenceStage.PENDING)));
        }

        @Test
        void rateLimitedFirst() {
            when(rateLimitService.tryAcquireLivenessSlot(USER_ID)).thenReturn(false);

            assertThat(service.submit(USER_ID, video(SESSION_ID.toString(), MP4)).resultCode())
                    .isEqualTo(EkycResultCode.RATE_LIMITED);
            verify(challengeStore, never()).consume(any());
        }

        @Test
        void missingOrWrongSessionIsChallengeExpired() {
            when(rateLimitService.tryAcquireLivenessSlot(USER_ID)).thenReturn(true);
            when(challengeStore.consume(USER_ID)).thenReturn(Optional.of(
                    new StoredChallenge(SESSION_ID, steps(), ATTEMPT_ID, NOW.toString())));

            EkycResultResponse result = service.submit(USER_ID, video(UUID.randomUUID().toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.CHALLENGE_EXPIRED);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.LIVENESS);
            verify(livenessClient, never()).analyze(any(), any());
        }

        @Test
        void noChallengeIsChallengeExpired() {
            when(rateLimitService.tryAcquireLivenessSlot(USER_ID)).thenReturn(true);
            when(challengeStore.consume(USER_ID)).thenReturn(Optional.empty());

            assertThat(service.submit(USER_ID, video(null, MP4)).resultCode()).isEqualTo(EkycResultCode.CHALLENGE_EXPIRED);
        }

        @Test
        void missingDraftIsDraftExpired() {
            when(rateLimitService.tryAcquireLivenessSlot(USER_ID)).thenReturn(true);
            when(challengeStore.consume(USER_ID)).thenReturn(Optional.of(
                    new StoredChallenge(SESSION_ID, steps(), ATTEMPT_ID, NOW.toString())));
            when(draftStore.find(USER_ID)).thenReturn(Optional.empty());

            assertThat(service.submit(USER_ID, video(SESSION_ID.toString(), MP4)).resultCode())
                    .isEqualTo(EkycResultCode.DRAFT_EXPIRED);
        }

        @Test
        void videoOverTenMegabytesIsPayloadTooLarge() {
            givenValidSession();
            byte[] huge = new byte[(int) EkycLivenessService.MAX_VIDEO_BYTES + 1];
            System.arraycopy(MP4, 0, huge, 0, MP4.length);

            assertConflict(() -> service.submit(USER_ID, video(SESSION_ID.toString(), huge)),
                    HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
            verify(livenessClient, never()).analyze(any(), any());
        }

        @Test
        void nonVideoBytesAreInvalidMedia() {
            givenValidSession();

            assertConflict(() -> service.submit(USER_ID, video(SESSION_ID.toString(), EkycTestData.JPEG_BYTES)),
                    HttpStatus.BAD_REQUEST, "INVALID_MEDIA");
        }

        @Test
        void replayedMediaIsLivenessFailedAndCountsAsFailure() {
            givenValidSession();
            when(counterStore.registerMedia(anyString())).thenReturn(false);
            when(counterStore.recordLivenessFailure(USER_ID)).thenReturn(1);

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_FAILED);
            assertThat(result.message()).isEqualTo("Video này đã được dùng trước đó.");
            assertThat(result.attemptsLeft()).isEqualTo(2);
            assertThat(result.liveness().reasons()).extracting(EkycResultResponse.LivenessReason::code)
                    .containsExactly("MEDIA_REPLAYED");
            assertThat(attempt.getLivenessResult()).isEqualTo(EkycLivenessResult.FAIL);
            verify(livenessClient, never()).analyze(any(), any());
        }

        @Test
        void aiFailureIsAiUnavailableAndGivesBackSlot() {
            givenValidSession();
            when(counterStore.registerMedia(anyString())).thenReturn(true);
            when(livenessClient.analyze(any(), any())).thenThrow(new AiLivenessException("busy", false, 503));

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.AI_UNAVAILABLE);
            verify(rateLimitService).releaseLivenessSlot(USER_ID);
            verify(evidenceService, never()).storeLiveness(anyLong(), any(), anyInt(), any(), anyList(), any());
        }

        @Test
        void passMapsToLivenessPassedWithConfirmStepAndSendsChallengeToAi() {
            givenValidSession();
            givenFreshMediaAndAi(result("PASS", List.of("ACTIONS_MATCH", "FACE_MATCH_HIGH"), 0.71, "HIGH"));

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_PASSED);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.CONFIRM);
            assertThat(result.liveness().result()).isEqualTo("PASSED");
            assertThat(result.liveness().faceMatchScore()).isEqualTo(0.71);
            assertThat(result.liveness().reasons()).as("mã tích cực không cần thông điệp").isEmpty();
            assertThat(result.draft()).isNotNull();
            assertThat(result.reviewRequired()).isFalse();
            assertThat(attempt.getLivenessResult()).isEqualTo(EkycLivenessResult.PASS);
            assertThat(attempt.getFaceMatchScore()).isEqualTo(0.71);
            assertThat(attempt.getThresholdsVersion()).isEqualTo("doc-2026-10-placeholder,liveness-2026-10-placeholder");
            verify(evidenceRepository).saveAll(anyList());

            ArgumentCaptor<LivenessSpec> spec = ArgumentCaptor.forClass(LivenessSpec.class);
            ArgumentCaptor<LivenessMedia> media = ArgumentCaptor.forClass(LivenessMedia.class);
            verify(livenessClient).analyze(spec.capture(), media.capture());
            assertThat(spec.getValue().steps()).hasSize(7);
            assertThat(spec.getValue().steps().get(1).action()).isEqualTo("TURN_LEFT");
            assertThat(spec.getValue().portraitEmbeddingB64()).isEqualTo(EkycTestData.EMBEDDING);
            assertThat(spec.getValue().captureMode()).isEqualTo("VIDEO");
            assertThat(spec.getValue().platform()).isEqualTo("android");
            assertThat(media.getValue().videoContentType()).isEqualTo("video/mp4");

            ArgumentCaptor<EkycIdentityDraft> saved = ArgumentCaptor.forClass(EkycIdentityDraft.class);
            verify(draftStore).save(eq(USER_ID), saved.capture());
            assertThat(saved.getValue().livenessResult()).isEqualTo(EkycLivenessResult.PASS);
            assertThat(saved.getValue().livenessRound()).isEqualTo(1);
            verify(counterStore, never()).recordLivenessFailure(any());
        }

        @Test
        void reviewMapsToLivenessReviewWithReviewRequired() {
            givenValidSession();
            givenFreshMediaAndAi(result("REVIEW", List.of("ACTIONS_MATCH", "FACE_MATCH_GRAY"), 0.41, "GRAY"));

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_REVIEW);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.CONFIRM);
            assertThat(result.reviewRequired()).isTrue();
            assertThat(result.liveness().reasons()).extracting(EkycResultResponse.LivenessReason::code)
                    .containsExactly("FACE_MATCH_GRAY");
        }

        @Test
        void retakeDoesNotCountAsFailureAndKeepsPreviousConclusion() {
            givenValidSession();
            givenFreshMediaAndAi(result("RETAKE", List.of("TOO_DARK"), null, null));

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.RETAKE);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.LIVENESS);
            assertThat(result.message()).isEqualTo("Ảnh quá tối, hãy ra chỗ sáng hơn.");
            assertThat(result.attemptsLeft()).isNull();
            assertThat(attempt.getLivenessResult()).isNull();
            verify(counterStore, never()).recordLivenessFailure(any());
            verify(evidenceRepository).saveAll(anyList());
        }

        @Test
        void failWithFaceMismatchReturnsFaceMismatchAndAttemptsLeft() {
            givenValidSession();
            givenFreshMediaAndAi(result("FAIL", List.of("ACTIONS_MATCH", "FACE_MISMATCH"), 0.12, "LOW"));
            when(counterStore.recordLivenessFailure(USER_ID)).thenReturn(1);

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.FACE_MISMATCH);
            assertThat(result.attemptsLeft()).isEqualTo(2);
            assertThat(result.liveness().result()).isEqualTo("FAILED");
            assertThat(attempt.getLivenessResult()).isEqualTo(EkycLivenessResult.FAIL);
            assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.IN_PROGRESS);
        }

        @Test
        void failWithoutFaceMismatchIsLivenessFailed() {
            givenValidSession();
            givenFreshMediaAndAi(result("FAIL", List.of("ACTIONS_EXTRA"), 0.6, "HIGH"));
            when(counterStore.recordLivenessFailure(USER_ID)).thenReturn(2);

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_FAILED);
            assertThat(result.attemptsLeft()).isEqualTo(1);
            assertThat(result.liveness().reasons()).extracting(EkycResultResponse.LivenessReason::message)
                    .containsExactly("Chưa làm đúng các động tác theo hướng dẫn.");
        }

        @Test
        void thirdFailureMovesAttemptAndProfileToManualReviewWithDraft() {
            givenValidSession();
            givenFreshMediaAndAi(result("FAIL", List.of("IDENTITY_INCONSISTENT"), 0.3, "GRAY"));
            when(counterStore.recordLivenessFailure(USER_ID)).thenReturn(3);
            when(evidenceRepository.findByAttemptIdOrderByIdAsc(100L)).thenReturn(List.of(
                    EkycTestData.evidence(1L, 100L, EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/a/cccd-front.jpg",
                            EkycEvidenceStage.PENDING, NOW)));
            when(evidenceService.storeResult(anyLong(), eq(ATTEMPT_ID), any())).thenReturn(new StoredEvidence(
                    EkycEvidenceKind.RESULT, null, "ekyc/dev/7/a/result.json", "f".repeat(64), 10L,
                    "application/json", EkycEvidenceStage.FINAL));

            EkycResultResponse result = service.submit(USER_ID, video(SESSION_ID.toString(), MP4));

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.REVIEW_PENDING);
            assertThat(result.status()).isEqualTo(EkycStatus.MANUAL_REVIEW);
            assertThat(result.nextStep()).isEqualTo(EkycNextStep.NONE);
            assertThat(result.attemptsLeft()).isZero();
            assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.MANUAL_REVIEW);
            assertThat(attempt.getStatus()).isEqualTo(EkycAttemptStatus.MANUAL_REVIEW);
            assertThat(attempt.getSubmittedAt()).isEqualTo(NOW);
            assertThat(attempt.getDraftEncrypted()).as("admin cần bản nháp để duyệt")
                    .contains(EkycTestData.ID_NUMBER).doesNotContain(EkycTestData.EMBEDDING);
            verify(evidenceService).markFinal(List.of("ekyc/dev/7/a/cccd-front.jpg"));
            verify(sessionCleanup).clearAll(USER_ID);
            verify(claimPublisher).publish(USER_ID, EkycStatus.MANUAL_REVIEW);
            verify(draftStore, never()).save(any(), any());
        }

        @Test
        void stillsModeSendsFramesAndTimes() {
            givenValidSession();
            givenFreshMediaAndAi(result("REVIEW", List.of("STILLS_MODE"), 0.6, "HIGH"));
            LivenessUpload upload = new LivenessUpload(SESSION_ID.toString(), "stills", "android", null,
                    List.of(new UploadedFile("f1.jpg", "image/jpeg", EkycTestData.JPEG_BYTES),
                            new UploadedFile("f2.jpg", "image/jpeg", EkycTestData.JPEG_BYTES)),
                    "[0, 1000]");

            EkycResultResponse result = service.submit(USER_ID, upload);

            assertThat(result.resultCode()).isEqualTo(EkycResultCode.LIVENESS_REVIEW);
            ArgumentCaptor<LivenessSpec> spec = ArgumentCaptor.forClass(LivenessSpec.class);
            ArgumentCaptor<LivenessMedia> media = ArgumentCaptor.forClass(LivenessMedia.class);
            verify(livenessClient).analyze(spec.capture(), media.capture());
            assertThat(spec.getValue().captureMode()).isEqualTo("STILLS");
            assertThat(spec.getValue().frameTimesMs()).containsExactly(0L, 1000L);
            assertThat(media.getValue().video()).isNull();
            assertThat(media.getValue().frames()).hasSize(2);
        }

        @Test
        void stillsWithMismatchedTimesAreInvalid() {
            givenValidSession();
            LivenessUpload upload = new LivenessUpload(SESSION_ID.toString(), "STILLS", "ios", null,
                    List.of(new UploadedFile("f1.jpg", "image/jpeg", EkycTestData.JPEG_BYTES)), "[0, 1000]");

            assertConflict(() -> service.submit(USER_ID, upload), HttpStatus.BAD_REQUEST, "INVALID_MEDIA");
        }

        @Test
        void stillsTimesMustStrictlyIncreaseAndFramesStayUnderAiLimit() {
            givenValidSession();
            List<UploadedFile> two = List.of(new UploadedFile("f1.jpg", "image/jpeg", EkycTestData.JPEG_BYTES),
                    new UploadedFile("f2.jpg", "image/jpeg", EkycTestData.JPEG_BYTES));
            assertConflict(() -> service.submit(USER_ID,
                            new LivenessUpload(SESSION_ID.toString(), "STILLS", "ios", null, two, "[1000, 1000]")),
                    HttpStatus.BAD_REQUEST, "INVALID_MEDIA");

            byte[] big = new byte[1_500_001];
            System.arraycopy(EkycTestData.JPEG_BYTES, 0, big, 0, EkycTestData.JPEG_BYTES.length);
            assertConflict(() -> service.submit(USER_ID, new LivenessUpload(SESSION_ID.toString(), "STILLS", "ios",
                            null, List.of(new UploadedFile("f.jpg", "image/jpeg", big)), "[0]")),
                    HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
        }
    }

    @Test
    void platformIsNormalizedForAi() {
        assertThat(EkycLivenessService.normalizePlatform(" iOS ")).isEqualTo("ios");
        assertThat(EkycLivenessService.normalizePlatform("ANDROID")).isEqualTo("android");
        assertThat(EkycLivenessService.normalizePlatform("windows")).isNull();
        assertThat(EkycLivenessService.normalizePlatform(null)).isNull();
    }

    @Test
    void detectsMovAndMp4Containers() {
        assertThat(EkycLivenessService.detectContainer(MP4)).isEqualTo("mp4");
        assertThat(EkycLivenessService.detectContainer(MOV)).isEqualTo("mov");
        assertThat(EkycLivenessService.detectContainer(EkycTestData.JPEG_BYTES)).isNull();
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static EkycConsent consent() {
        return EkycConsent.accept(PROFILE_ID, "ekyc-2026-10", List.of("ID_DOCUMENT"), "a".repeat(64), "ios", "1", NOW);
    }

    private static List<LivenessStep> steps() {
        return List.of(
                new LivenessStep(LivenessAction.FRONTAL, 1500), new LivenessStep(LivenessAction.TURN_LEFT, 1300),
                new LivenessStep(LivenessAction.FRONTAL, 1100), new LivenessStep(LivenessAction.LOOK_UP, 1600),
                new LivenessStep(LivenessAction.FRONTAL, 900), new LivenessStep(LivenessAction.TURN_RIGHT, 1400),
                new LivenessStep(LivenessAction.FRONTAL, 1000));
    }

    private static LivenessUpload video(String sessionId, byte[] bytes) {
        return new LivenessUpload(sessionId, "VIDEO", "android",
                new UploadedFile("liveness.mp4", "video/mp4", bytes), List.of(), null);
    }

    private static LivenessResult result(String decision, List<String> reasons, Double score, String zone) {
        return new LivenessResult(decision, reasons, null, null, null, null, new FaceMatch(score, zone), null,
                List.of(new EvidenceFrame("FRONTAL", EkycTestData.JPEG_BASE64)), "liveness-2026-10-placeholder");
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                       HttpStatus status, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }
}
