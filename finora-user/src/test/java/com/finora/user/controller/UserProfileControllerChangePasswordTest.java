package com.finora.user.controller;

import com.finora.user.dto.request.ChangePasswordRequest;
import com.finora.user.service.AuthService;
import com.finora.user.service.EkycVerificationService;
import com.finora.user.service.KycSummaryService;
import com.finora.user.service.UserProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /api/v1/users/me/change-password}: mọi tài khoản đăng nhập (kể cả nhân viên) tự đổi được mật khẩu. */
@ExtendWith(MockitoExtension.class)
class UserProfileControllerChangePasswordTest {

    private static final String PATH = "/api/v1/users/me/change-password";

    @Mock private UserProfileService userProfileService;
    @Mock private EkycVerificationService ekycVerificationService;
    @Mock private KycSummaryService kycSummaryService;
    @Mock private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new UserProfileController(userProfileService, ekycVerificationService,
                kycSummaryService, authService));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void staffChangesOwnPassword() throws Exception {
        authenticateWith("ROLE_STAFF", "user:profile:read");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"TamThoi#2345\",\"newPassword\":\"MatKhauMoi!9\"}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ChangePasswordRequest> request = ArgumentCaptor.forClass(ChangePasswordRequest.class);
        verify(authService).changePassword(eq(USER_ID), request.capture(), anyString());
        assertThat(request.getValue().getNewPassword()).isEqualTo("MatKhauMoi!9");
    }

    @Test
    void shortNewPasswordIsBadRequest() throws Exception {
        authenticateWith("ROLE_STAFF", "user:profile:read");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"TamThoi#2345\",\"newPassword\":\"ngan\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(authService);
    }

    @Test
    void tokenWithoutProfileAuthorityIsForbidden() throws Exception {
        authenticateWith("notification:internal:send");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"TamThoi#2345\",\"newPassword\":\"MatKhauMoi!9\"}"))
                .andExpect(status().isForbidden());
        verify(authService, org.mockito.Mockito.never()).changePassword(any(), any(), anyString());
    }
}
