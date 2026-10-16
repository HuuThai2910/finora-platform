package com.finora.user.service;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.EkycConsentProperties;
import com.finora.user.config.EkycStorageProperties;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycAttemptStatus;
import com.finora.user.domain.EkycConsent;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.request.EkycConsentAcceptRequest;
import com.finora.user.dto.response.EkycConsentResponse;
import com.finora.user.repository.EkycAttemptRepository;
import com.finora.user.repository.EkycConsentRepository;
import com.finora.user.repository.EkycEvidenceRepository;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.support.EkycTestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Kiểm tra xem, đồng ý và rút lại đồng ý điều khoản eKYC (EKYC-002 mục 3.1). */
@ExtendWith(MockitoExtension.class)
class EkycConsentServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Long PROFILE_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

    @Mock private UserProfileRepository userProfileRepository;
    @Mock private EkycConsentRepository consentRepository;
    @Mock private EkycAttemptRepository attemptRepository;
    @Mock private EkycEvidenceRepository evidenceRepository;
    @Mock private EkycEvidenceService evidenceService;
    @Mock private EkycSessionCleanup sessionCleanup;
    @Mock private EkycClaimPublisher claimPublisher;

    private final EkycConsentDocument document =
            new EkycConsentDocument(new EkycConsentProperties(), new EkycStorageProperties());
    private UserProfile profile;
    private EkycConsentService service;

    @BeforeEach
    void setUp() {
        profile = UserProfile.builder().id(PROFILE_ID).keycloakUserId(USER_ID).email("nguoidung@example.test")
                .ekycStatus(EkycStatus.PENDING).build();
        lenient().when(userProfileRepository.findByKeycloakUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(userProfileRepository.findById(PROFILE_ID)).thenReturn(Optional.of(profile));
        service = new EkycConsentService(userProfileRepository, consentRepository, attemptRepository, evidenceRepository,
                document, evidenceService, sessionCleanup, claimPublisher, EkycTestData.transactionTemplate(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EkycConsent currentConsent() {
        return EkycConsent.accept(PROFILE_ID, document.version(), EkycConsentDocument.PURPOSES,
                document.textSha256(), "ios", "1.0", NOW.minusSeconds(60));
    }

    @Test
    void getReturnsRenderedTermsAndNotAcceptedWithoutRecord() {
        EkycConsentResponse response = service.getConsent(USER_ID);

        assertThat(response.version()).isEqualTo("ekyc-2026-10");
        assertThat(response.title()).isEqualTo("Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)");
        assertThat(response.consentLabel())
                .isEqualTo("Tôi đã đọc và đồng ý với Điều khoản xử lý dữ liệu khi xác minh danh tính.");
        assertThat(response.purposes()).containsExactly("ID_DOCUMENT", "FACE_BIOMETRIC", "STORAGE_ABROAD", "OWN_DOCUMENT");
        assertThat(response.textSha256()).isEqualTo(document.textSha256());
        assertThat(response.body()).isEqualTo(document.body());
        assertThat(response.accepted()).isFalse();
        assertThat(response.acceptedAt()).isNull();
    }

    @Test
    void consentForOldTextIsNotValid() {
        EkycConsent stale = EkycConsent.accept(PROFILE_ID, document.version(), EkycConsentDocument.PURPOSES,
                "0".repeat(64), "ios", "1.0", NOW);
        when(consentRepository.findFirstByUserProfileIdAndWithdrawnAtIsNullOrderByAcceptedAtDescIdDesc(PROFILE_ID))
                .thenReturn(Optional.of(stale));

        assertThat(service.findActiveConsent(PROFILE_ID)).isEmpty();
        assertThat(service.getConsent(USER_ID).accepted()).isFalse();
    }

    @Test
    void currentConsentIsValid() {
        EkycConsent current = currentConsent();
        when(consentRepository.findFirstByUserProfileIdAndWithdrawnAtIsNullOrderByAcceptedAtDescIdDesc(PROFILE_ID))
                .thenReturn(Optional.of(current));

        EkycConsentResponse response = service.getConsent(USER_ID);

        assertThat(response.accepted()).isTrue();
        assertThat(response.acceptedAt()).isEqualTo(current.getAcceptedAt());
    }

    @Test
    void acceptRecordsAllPurposesWithTheExactHash() {
        when(consentRepository.save(any(EkycConsent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EkycConsentResponse response = service.accept(USER_ID, new EkycConsentAcceptRequest(
                document.version(), document.textSha256().toUpperCase(), "ANDROID", "2.1.0"));

        assertThat(response.accepted()).isTrue();
        assertThat(response.acceptedAt()).isEqualTo(NOW);
        ArgumentCaptor<EkycConsent> saved = ArgumentCaptor.forClass(EkycConsent.class);
        verify(consentRepository).save(saved.capture());
        assertThat(saved.getValue().getTextSha256()).isEqualTo(document.textSha256());
        assertThat(saved.getValue().purposeList()).containsExactly("ID_DOCUMENT", "FACE_BIOMETRIC", "STORAGE_ABROAD", "OWN_DOCUMENT");
        assertThat(saved.getValue().getPlatform()).isEqualTo("android");
        assertThat(saved.getValue().getAppVersion()).isEqualTo("2.1.0");
    }

    @Test
    void acceptingStaleVersionOrHashIsConflict() {
        assertThatThrownBy(() -> service.accept(USER_ID,
                new EkycConsentAcceptRequest("ekyc-2026-09", document.textSha256(), "ios", "1")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("CONSENT_VERSION_STALE");
                });
        assertThatThrownBy(() -> service.accept(USER_ID,
                new EkycConsentAcceptRequest(document.version(), "f".repeat(64), "ios", "1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CONSENT_VERSION_STALE"));
        verify(consentRepository, never()).save(any());
    }

    @Test
    void acceptingTwiceReturnsExistingRecord() {
        when(consentRepository.findFirstByUserProfileIdAndWithdrawnAtIsNullOrderByAcceptedAtDescIdDesc(PROFILE_ID))
                .thenReturn(Optional.of(currentConsent()));

        EkycConsentResponse response = service.accept(USER_ID,
                new EkycConsentAcceptRequest(document.version(), document.textSha256(), "ios", "1"));

        assertThat(response.accepted()).isTrue();
        verify(consentRepository, never()).save(any());
    }

    @Test
    void withdrawResetsVerifiedProfileKeepsIdHashAndDeletesEvidence() {
        profile.markEkycVerified(0.7, true, NOW.minusSeconds(3600));
        profile.setIdNumberHash(EkycTestData.hash(EkycTestData.ID_NUMBER));
        profile.setIdNumberEncrypted(EkycTestData.ID_NUMBER);
        EkycConsent consent = currentConsent();
        EkycAttempt open = EkycTestData.attempt(1L, UUID.randomUUID(), PROFILE_ID, EkycDocumentDecision.AUTO_OK, NOW);
        EkycAttempt review = EkycTestData.attempt(2L, UUID.randomUUID(), PROFILE_ID, EkycDocumentDecision.NEEDS_REVIEW, NOW);
        review.submitForReview("{\"draft\":true}", NOW);
        when(consentRepository.findByUserProfileIdAndWithdrawnAtIsNull(PROFILE_ID)).thenReturn(List.of(consent));
        when(attemptRepository.findByUserProfileIdAndStatusIn(eq(PROFILE_ID), anyCollection())).thenReturn(List.of(open, review));
        when(attemptRepository.findByUserProfileIdAndDraftEncryptedIsNotNull(PROFILE_ID)).thenReturn(List.of(review));
        when(evidenceService.deleteUserObjects(PROFILE_ID)).thenReturn(true);
        when(evidenceService.envPrefix()).thenReturn("ekyc/dev/");
        when(evidenceRepository.findLiveByUserProfileId(PROFILE_ID, "ekyc/dev/%")).thenReturn(List.of(
                EkycTestData.evidence(11L, 2L, EkycEvidenceKind.CCCD_FRONT, "ekyc/dev/7/x/cccd-front.jpg",
                        EkycEvidenceStage.FINAL, NOW)));

        EkycConsentResponse response = service.withdraw(USER_ID);

        assertThat(response.accepted()).isFalse();
        assertThat(consent.getWithdrawnAt()).isEqualTo(NOW);
        assertThat(profile.getEkycStatus()).isEqualTo(EkycStatus.PENDING);
        assertThat(profile.isDocumentVerified()).isFalse();
        assertThat(profile.isLivenessVerified()).isFalse();
        assertThat(profile.getFaceMatchScore()).isNull();
        assertThat(profile.getIdNumberHash()).as("giữ hash chống một CCCD nhiều tài khoản").isNotNull();
        assertThat(profile.getIdNumberEncrypted()).isEqualTo(EkycTestData.ID_NUMBER);
        assertThat(open.getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
        assertThat(review.getStatus()).isEqualTo(EkycAttemptStatus.ABANDONED);
        assertThat(review.getDraftEncrypted()).isNull();
        verify(sessionCleanup).clearAll(USER_ID);
        verify(claimPublisher).publish(USER_ID, EkycStatus.PENDING);
        verify(evidenceRepository).markDeleted(List.of(11L), NOW);
    }

    @Test
    void storageFailureDuringWithdrawalLeavesEvidenceForTheSweeper() {
        when(consentRepository.findByUserProfileIdAndWithdrawnAtIsNull(PROFILE_ID)).thenReturn(List.of(currentConsent()));
        when(attemptRepository.findByUserProfileIdAndStatusIn(eq(PROFILE_ID), anyCollection())).thenReturn(List.of());
        when(attemptRepository.findByUserProfileIdAndDraftEncryptedIsNotNull(PROFILE_ID)).thenReturn(List.of());
        when(evidenceService.deleteUserObjects(PROFILE_ID)).thenReturn(false);

        EkycConsentResponse response = service.withdraw(USER_ID);

        assertThat(response.accepted()).isFalse();
        verify(evidenceRepository, never()).markDeleted(any(), any());
        verify(evidenceRepository, never()).findLiveByUserProfileId(any(), anyString());
        // Hồ sơ PENDING → PENDING: không cần ghi claim.
        verify(claimPublisher, never()).publish(any(), any());
    }
}
