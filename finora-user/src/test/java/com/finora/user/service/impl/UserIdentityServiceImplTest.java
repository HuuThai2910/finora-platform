package com.finora.user.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.UserProfile;
import com.finora.user.dto.response.UserIdentityResponse;
import com.finora.user.repository.UserProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Danh tính cho giấy chuyển nhượng Note: tra theo ID hồ sơ hoặc Keycloak UUID, CCCD chỉ giữ 4 số cuối. */
class UserIdentityServiceImplTest {

    private static final UUID KEYCLOAK_ID = UUID.fromString("6f1c8a2e-0000-4000-8000-000000000042");

    private final UserProfileRepository repository = mock(UserProfileRepository.class);
    private final UserIdentityServiceImpl service = new UserIdentityServiceImpl(repository);

    @Test
    void numericUserIdIsLookedUpByProfileId() {
        when(repository.findById(42L)).thenReturn(Optional.of(profile("001099012345")));

        UserIdentityResponse identity = service.identityOf("42");

        assertThat(identity.userId()).isEqualTo("42");
        assertThat(identity.fullName()).isEqualTo("Trần Thị Huệ");
        assertThat(identity.maskedIdNumber()).isEqualTo("********2345").hasSize(12);
    }

    @Test
    void uuidUserIdFallsBackToKeycloakUserIdForLegacyTokens() {
        // Tài khoản cũ chưa có attribute user_id: Investment lưu sub (Keycloak UUID) làm investorId.
        when(repository.findByKeycloakUserId(KEYCLOAK_ID)).thenReturn(Optional.of(profile("079203000777")));

        UserIdentityResponse identity = service.identityOf(KEYCLOAK_ID.toString());

        assertThat(identity.userId()).isEqualTo(KEYCLOAK_ID.toString());
        assertThat(identity.maskedIdNumber()).isEqualTo("********0777");
    }

    @Test
    void unknownOrMalformedUserIdIsNotFound() {
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.identityOf("7")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.identityOf("INVESTOR-001")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.identityOf("99999999999999999999"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void malformedIdNeverHitsTheRepository() {
        assertThatThrownBy(() -> service.identityOf("not-a-uuid")).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }

    private static UserProfile profile(String idNumber) {
        return UserProfile.builder()
                .id(42L)
                .keycloakUserId(KEYCLOAK_ID)
                .email("hue@example.test")
                .fullName("Trần Thị Huệ")
                .idNumberEncrypted(idNumber)
                .build();
    }
}
