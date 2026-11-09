package com.finora.notification.controller;

import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.notification.config.NotificationSecurityConfig;
import com.finora.notification.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D36: endpoint nội bộ gửi email chỉ nhận service account có {@code notification:internal:send}. Trước đây
 * {@code permitAll}: ai gọi được cổng 8086 cũng bắt FINORA gửi "mã OTP" tới địa chỉ bất kỳ.
 */
@WebMvcTest(NotificationController.class)
@Import({NotificationSecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class NotificationControllerSecurityTest {

    private static final String OTP_BODY = "{\"email\":\"nguoi.dung@example.com\",\"otp\":\"123456\"}";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EmailService emailService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void anonymousCallerCannotMakeFinoraSendAnOtpEmail() throws Exception {
        mvc.perform(post("/api/internal/notifications/otp-email").contentType(MediaType.APPLICATION_JSON)
                .content(OTP_BODY)).andExpect(status().isUnauthorized());

        verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString());
    }

    @Test
    void signedInUserWithoutTheServiceRoleIsForbidden() throws Exception {
        mvc.perform(post("/api/internal/notifications/otp-email").contentType(MediaType.APPLICATION_JSON)
                        .content(OTP_BODY).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BORROWER"))))
                .andExpect(status().isForbidden());

        verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString());
    }

    @Test
    void userServiceAccountSendsTheOtpEmail() throws Exception {
        mvc.perform(post("/api/internal/notifications/otp-email").contentType(MediaType.APPLICATION_JSON)
                        .content(OTP_BODY).with(serviceAccount()))
                .andExpect(status().isOk());

        verify(emailService).sendPasswordResetOtp("nguoi.dung@example.com", "123456");
    }

    @Test
    void otpMustBeSixDigitsSoTheMailCannotCarryArbitraryText() throws Exception {
        mvc.perform(post("/api/internal/notifications/otp-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nguoi.dung@example.com\",\"otp\":\"Bấm vào link này\"}")
                        .with(serviceAccount()))
                .andExpect(status().isBadRequest());

        verify(emailService, never()).sendPasswordResetOtp(anyString(), anyString());
    }

    @Test
    void alertNeedsAValidAddress() throws Exception {
        mvc.perform(post("/api/internal/notifications/suspicious-activity-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"khong-phai-email\",\"ipAddress\":\"10.0.0.1\",\"reason\":\"5 lần sai\"}")
                        .with(serviceAccount()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void staffAccountEmailCarriesOnlyGeneratedPasswordCharacters() throws Exception {
        String valid = "{\"email\":\"nv.nam@finora.vn\",\"fullName\":\"Lê Văn Nam\",\"roleName\":\"Nhân viên (Thu hồi nợ)\","
                + "\"temporaryPassword\":\"Ab3$kLmn-Pq_R7x\"}";
        mvc.perform(post("/api/internal/notifications/staff-account-email").contentType(MediaType.APPLICATION_JSON)
                        .content(valid).with(serviceAccount()))
                .andExpect(status().isOk());
        verify(emailService).sendStaffAccountEmail("nv.nam@finora.vn", "Lê Văn Nam", "Nhân viên (Thu hồi nợ)",
                "Ab3$kLmn-Pq_R7x");

        mvc.perform(post("/api/internal/notifications/staff-account-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nv.nam@finora.vn\",\"roleName\":\"Nhân viên\","
                                + "\"temporaryPassword\":\"<a href=x>Bấm vào đây</a>\"}")
                        .with(serviceAccount()))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/internal/notifications/staff-account-email").contentType(MediaType.APPLICATION_JSON)
                        .content(valid).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor serviceAccount() {
        return jwt().jwt(token -> token.claim("azp", "finora-user-client"))
                .authorities(new SimpleGrantedAuthority(NotificationSecurityConfig.INTERNAL_SEND_AUTHORITY));
    }
}
