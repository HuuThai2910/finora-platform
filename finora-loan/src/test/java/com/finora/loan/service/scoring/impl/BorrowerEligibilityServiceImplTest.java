package com.finora.loan.service.scoring.impl;

import com.finora.loan.config.BorrowerEligibilityProperties;
import com.finora.loan.domain.application.entity.BorrowerKycSnapshot;
import com.finora.loan.domain.scoring.enums.BorrowerKycStatus;
import com.finora.loan.domain.scoring.enums.BorrowerProfileSource;
import com.finora.loan.domain.scoring.enums.EligibilityResult;
import com.finora.loan.domain.scoring.enums.IncomeVerificationStatus;
import com.finora.loan.integration.profile.client.BorrowerProfileProvider;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import com.finora.loan.service.scoring.BorrowerEligibilityDecision;
import com.finora.loan.service.scoring.BorrowerEligibilityService;
import com.finora.loan.service.scoring.BorrowerEligibilityStateService;
import com.finora.loan.util.HashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W2-KYC: worker xét điều kiện từ snapshot eKYC lúc nộp, không gọi lại User và không đoán khi thiếu snapshot. */
class BorrowerEligibilityServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    private final BorrowerProfileProvider provider = mock(BorrowerProfileProvider.class);
    private final BorrowerEligibilityStateService stateService = mock(BorrowerEligibilityStateService.class);
    private final HashingService hashing = mock(HashingService.class);
    private BorrowerEligibilityService service;

    @BeforeEach
    void setUp() {
        when(hashing.sha256(any())).thenReturn("h".repeat(64));
        service = new BorrowerEligibilityServiceImpl(provider,
                new BorrowerEligibilityPolicyImpl(new BorrowerEligibilityProperties(18, 65, "TEST_POLICY")),
                stateService, new BorrowerEligibilityProperties(18, 65, "TEST_POLICY"), hashing,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void usesSubmissionSnapshotWithoutCallingProvider() {
        service.evaluate(7L, "BORROWER-7", BorrowerKycSnapshot.capture(BorrowerKycStatus.VERIFIED, 27,
                "USER-KYC-BORROWER-7", "USER_KYC_SUMMARY_V1", IncomeVerificationStatus.NOT_VERIFIED,
                BorrowerProfileSource.USER_SERVICE, NOW.minusSeconds(60)));

        verify(provider, never()).getBorrowerProfile(anyString(), any());
        BorrowerProfileResult profile = persistedProfile();
        assertThat(profile.borrowerId()).isEqualTo("BORROWER-7");
        assertThat(profile.age()).isEqualTo(27);
        assertThat(profile.source()).isEqualTo(BorrowerProfileSource.USER_SERVICE);
        assertThat(persistedDecision().result()).isEqualTo(EligibilityResult.ELIGIBLE);
    }

    @Test
    void legacyApplicationUsesBackgroundCapableProvider() {
        when(provider.supportsBackgroundLookup()).thenReturn(true);
        when(provider.getBorrowerProfile(eq("BORROWER-8"), any())).thenReturn(new BorrowerProfileResult(
                "BORROWER-8", null, 30, BorrowerKycStatus.VERIFIED, "MOCK-KYC-BORROWER-8", "MOCK-V1",
                IncomeVerificationStatus.NOT_VERIFIED, BorrowerProfileSource.MOCK_USER_PROFILE, NOW));

        service.evaluate(8L, "BORROWER-8", null);

        assertThat(persistedProfile().source()).isEqualTo(BorrowerProfileSource.MOCK_USER_PROFILE);
        assertThat(persistedDecision().result()).isEqualTo(EligibilityResult.ELIGIBLE);
    }

    @Test
    void legacyApplicationWithoutSnapshotIsNotAssumedVerifiedWhenProviderNeedsBorrowerToken() {
        when(provider.supportsBackgroundLookup()).thenReturn(false);

        service.evaluate(9L, "BORROWER-9", null);

        verify(provider, never()).getBorrowerProfile(anyString(), any());
        assertThat(persistedProfile().kycStatus()).isEqualTo(BorrowerKycStatus.PENDING);
        BorrowerEligibilityDecision decision = persistedDecision();
        assertThat(decision.result()).isEqualTo(EligibilityResult.INVALID_PROFILE);
        assertThat(decision.reasonCode()).isEqualTo("KYC_SNAPSHOT_MISSING");
    }

    private BorrowerProfileResult persistedProfile() {
        ArgumentCaptor<BorrowerProfileResult> profile = ArgumentCaptor.forClass(BorrowerProfileResult.class);
        verify(stateService).persist(anyLong(), anyString(), anyString(), anyString(), profile.capture(), any(),
                anyString(), any());
        return profile.getValue();
    }

    private BorrowerEligibilityDecision persistedDecision() {
        ArgumentCaptor<BorrowerEligibilityDecision> decision = ArgumentCaptor.forClass(BorrowerEligibilityDecision.class);
        verify(stateService).persist(anyLong(), anyString(), anyString(), anyString(), any(), decision.capture(),
                anyString(), any());
        return decision.getValue();
    }
}
