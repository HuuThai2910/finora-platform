package com.finora.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.KycSummaryResponse;
import com.finora.user.repository.UserProfileRepository;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** W2-KYC: tóm tắt eKYC cho Loan chỉ có trạng thái và tuổi theo ngày Việt Nam, không có dữ liệu định danh. */
class KycSummaryServiceImplTest {

    // 01:00 ngày 17/10 giờ Việt Nam, vẫn là ngày 16/10 theo UTC.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T18:00:00Z"), ZoneOffset.UTC);
    private static final UUID KEYCLOAK_ID = UUID.fromString("6f1c8a2e-0000-4000-8000-000000000042");

    private final UserProfileRepository repository = mock(UserProfileRepository.class);
    private final KycSummaryServiceImpl service = new KycSummaryServiceImpl(repository, CLOCK);

    @Test
    void returnsStatusAndAgeOnTheVietnameseDate() {
        when(repository.findByKeycloakUserId(KEYCLOAK_ID)).thenReturn(Optional.of(UserProfile.builder()
                .id(42L)
                .keycloakUserId(KEYCLOAK_ID)
                .email("an@example.test")
                .fullName("Nguyễn Văn An")
                .dateOfBirth(LocalDate.of(2000, 10, 17))
                .ekycStatus(EkycStatus.VERIFIED)
                .documentVerified(true)
                .ekycCompletedAt(Instant.parse("2026-10-01T02:00:00Z"))
                .build()));

        KycSummaryResponse summary = service.summaryOf(KEYCLOAK_ID);

        assertThat(summary.userId()).isEqualTo("42");
        assertThat(summary.ekycStatus()).isEqualTo(EkycStatus.VERIFIED);
        assertThat(summary.documentVerified()).isTrue();
        assertThat(summary.ekycCompletedAt()).isEqualTo(Instant.parse("2026-10-01T02:00:00Z"));
        // Sinh nhật 17/10 đã tới theo giờ Việt Nam; tính theo ngày UTC sẽ ra 25.
        assertThat(summary.age()).isEqualTo(26);
    }

    @Test
    void pendingProfileHasNoAgeUntilEkycProvidesDateOfBirth() {
        when(repository.findByKeycloakUserId(KEYCLOAK_ID)).thenReturn(Optional.of(UserProfile.builder()
                .id(43L).keycloakUserId(KEYCLOAK_ID).email("binh@example.test")
                .ekycStatus(EkycStatus.PENDING).build()));

        KycSummaryResponse summary = service.summaryOf(KEYCLOAK_ID);

        assertThat(summary.ekycStatus()).isEqualTo(EkycStatus.PENDING);
        assertThat(summary.age()).isNull();
        assertThat(summary.ekycCompletedAt()).isNull();
    }

    @Test
    void unknownUserIsNotFound() {
        when(repository.findByKeycloakUserId(KEYCLOAK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.summaryOf(KEYCLOAK_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void contractCarriesNoIdentityData() {
        assertThat(Arrays.stream(KycSummaryResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("userId", "ekycStatus", "documentVerified", "ekycCompletedAt", "age");
    }
}
