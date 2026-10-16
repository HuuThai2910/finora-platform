package com.finora.user.controller;

import com.finora.user.dto.request.EkycConsentAcceptRequest;
import com.finora.user.dto.response.EkycConsentResponse;
import com.finora.user.service.EkycConsentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Biên HTTP của điều khoản eKYC (EKYC-002 mục 3.1). */
@ExtendWith(MockitoExtension.class)
class EkycConsentControllerTest {

    private static final String BASE = "/api/v1/users/profile/ekyc/consent";
    private static final String HASH = "a".repeat(64);

    @Mock
    private EkycConsentService consentService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new EkycConsentController(consentService));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getReturnsTermsWrappedInBaseResponse() throws Exception {
        authenticateWith("user:cccd:scan");
        when(consentService.getConsent(USER_ID)).thenReturn(response(false, null));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.version").value("ekyc-2026-10"))
                .andExpect(jsonPath("$.data.purposes[1]").value("FACE_BIOMETRIC"))
                .andExpect(jsonPath("$.data.textSha256").value(HASH))
                .andExpect(jsonPath("$.data.accepted").value(false))
                .andExpect(jsonPath("$.data.acceptedAt").isEmpty());
    }

    @Test
    void acceptValidatesBodyBeforeCallingService() throws Exception {
        authenticateWith("user:cccd:scan");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"ekyc-2026-10\",\"textSha256\":\"không-phải-hash\",\"platform\":\"ios\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"ekyc-2026-10\",\"textSha256\":\"" + HASH + "\",\"platform\":\"windows\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(consentService);
    }

    @Test
    void acceptAndWithdraw() throws Exception {
        authenticateWith("user:cccd:scan");
        when(consentService.accept(any(), any(EkycConsentAcceptRequest.class)))
                .thenReturn(response(true, Instant.parse("2026-10-16T03:00:00Z")));
        when(consentService.withdraw(USER_ID)).thenReturn(response(false, null));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"ekyc-2026-10\",\"textSha256\":\"" + HASH + "\",\"platform\":\"android\",\"appVersion\":\"1.2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.acceptedAt").value("2026-10-16T03:00:00Z"));
        mockMvc.perform(delete(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(false));
        verify(consentService).withdraw(USER_ID);
    }

    @Test
    void withoutScanAuthorityIsForbidden() throws Exception {
        authenticateWith("user:profile:read");

        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        mockMvc.perform(delete(BASE)).andExpect(status().isForbidden());
        verifyNoInteractions(consentService);
    }

    private static EkycConsentResponse response(boolean accepted, Instant acceptedAt) {
        return new EkycConsentResponse("ekyc-2026-10", "Điều khoản xử lý dữ liệu khi xác minh danh tính (eKYC)",
                "### Điều khoản", "Tôi đã đọc và đồng ý với Điều khoản xử lý dữ liệu khi xác minh danh tính.",
                List.of("ID_DOCUMENT", "FACE_BIOMETRIC", "STORAGE_ABROAD", "OWN_DOCUMENT"), HASH, accepted, acceptedAt);
    }
}
