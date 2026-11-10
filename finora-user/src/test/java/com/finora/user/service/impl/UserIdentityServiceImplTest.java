package com.finora.user.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.dto.response.UserFullNameResponse;
import com.finora.user.dto.response.UserIdMatchResponse;
import com.finora.user.dto.response.UserIdentityResponse;
import com.finora.user.repository.UserFullNameRow;
import com.finora.user.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void fullNamesAreReadInTwoBatchQueriesAndKeepTheCallerIds() {
        when(repository.findFullNamesByIdIn(List.of(42L, 7L)))
                .thenReturn(List.of(new UserFullNameRow(42L, UUID.randomUUID(), "Trần Thị Huệ")));
        when(repository.findFullNamesByKeycloakUserIdIn(List.of(KEYCLOAK_ID)))
                .thenReturn(List.of(new UserFullNameRow(43L, KEYCLOAK_ID, "Lê Văn Bình")));

        List<UserFullNameResponse> names = service.fullNamesOf(List.of(
                "42", "42", " ", "7", KEYCLOAK_ID.toString().toUpperCase(), "INVESTOR-001"));

        // ID trùng gửi một lần; ID không có hồ sơ hay sai dạng bị bỏ, không làm hỏng cả lô.
        assertThat(names).containsExactly(
                new UserFullNameResponse("42", "Trần Thị Huệ"),
                new UserFullNameResponse(KEYCLOAK_ID.toString().toUpperCase(), "Lê Văn Bình"));
        verify(repository, never()).findById(any());
    }

    @Test
    void fullNamesSkipQueriesWhenNothingIsUsable() {
        assertThat(service.fullNamesOf(List.of("not-a-uuid"))).isEmpty();
        verify(repository, never()).findFullNamesByIdIn(any());
        verify(repository, never()).findFullNamesByKeycloakUserIdIn(any());
    }

    @Test
    void idsByNameNormalizeTheQueryAndReturnBothIdForms() {
        when(repository.findFullNamesByPlainNameLike(any(), any(), eq("%tran thi%"), any()))
                .thenReturn(List.of(new UserFullNameRow(42L, KEYCLOAK_ID, "Trần Thị Huệ"),
                        new UserFullNameRow(43L, null, "Trần Thị Bình")));
        when(repository.findFullNamesByNameContaining(any(), any())).thenCallRealMethod();

        List<UserIdMatchResponse> matches = service.userIdsByName("  Trần   THỊ ", 200);

        assertThat(matches).containsExactly(
                new UserIdMatchResponse("42", KEYCLOAK_ID.toString()),
                new UserIdMatchResponse("43", null));
        verify(repository).findFullNamesByPlainNameLike(any(), any(), eq("%tran thi%"),
                eq(org.springframework.data.domain.PageRequest.of(0, 200)));
    }

    @Test
    void idsByNameEscapeLikeWildcardsAndSkipBlankQueries() {
        when(repository.findFullNamesByNameContaining(any(), any())).thenCallRealMethod();
        when(repository.findFullNamesByPlainNameLike(any(), any(), any(), any())).thenReturn(List.of());

        service.userIdsByName("50%_an", 10);

        verify(repository).findFullNamesByPlainNameLike(any(), any(), eq("%50!%!_an%"), any());
        assertThat(service.userIdsByName(" ", 10)).isEmpty();
        assertThat(service.userIdsByName(null, 10)).isEmpty();
    }
}
