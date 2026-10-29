package com.finora.user.service.impl;

import com.finora.common.enums.user.UserRole;
import com.finora.common.exception.BusinessException;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.PendingRegistration;
import com.finora.user.dto.request.RegisterRequest;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.KeycloakAdminService;
import com.finora.user.service.NotificationService;
import com.finora.user.service.PendingRegistrationStore;
import com.finora.user.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tự đăng ký chỉ ra người vay hoặc nhà đầu tư: body đăng ký mang ADMIN/STAFF là đường tự nâng quyền.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplRegisterRoleTest {

    @Mock private KeycloakAdminService keycloakAdminService;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private RateLimitService rateLimitService;
    @Mock private NotificationService notificationService;
    @Mock private PendingRegistrationStore pendingRegistrationStore;
    @Mock private CryptoProperties cryptoProperties;

    @InjectMocks private AuthServiceImpl authService;

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"ADMIN", "STAFF"})
    void privilegedRoleIsRejectedBeforeAnySideEffect(UserRole role) {
        RegisterRequest request = new RegisterRequest("moi@example.test", "MatKhau123!", null, null, role);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getCode()).isEqualTo("ROLE_NOT_ALLOWED");
                });
        verifyNoInteractions(rateLimitService, pendingRegistrationStore, notificationService, keycloakAdminService);
    }

    @Test
    void nullRoleDefaultsToBorrowerAndInvestorIsKept() {
        when(rateLimitService.recordOtpRequest(anyString())).thenReturn(true);

        authService.register(new RegisterRequest("vay@example.test", "MatKhau123!", null, null, null));
        authService.register(new RegisterRequest("dautu@example.test", "MatKhau123!", null, null, UserRole.INVESTOR));

        ArgumentCaptor<PendingRegistration> saved = ArgumentCaptor.forClass(PendingRegistration.class);
        verify(pendingRegistrationStore, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PendingRegistration::role)
                .containsExactly(UserRole.BORROWER, UserRole.INVESTOR);
    }
}
