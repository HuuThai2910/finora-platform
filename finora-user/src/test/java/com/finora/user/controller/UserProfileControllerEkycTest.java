package com.finora.user.controller;

import com.finora.common.enums.user.EkycResultCode;
import com.finora.common.enums.user.EkycStatus;
import com.finora.common.exception.BusinessException;
import com.finora.user.dto.request.EkycVerifyRequest;
import com.finora.user.dto.response.EkycNextStep;
import com.finora.user.dto.response.EkycResultResponse;
import com.finora.user.dto.response.MyEkycStatusResponse;
import com.finora.user.service.AuthService;
import com.finora.user.service.EkycReviewWithdrawalService;
import com.finora.user.service.EkycVerificationService;
import com.finora.user.service.KycSummaryService;
import com.finora.user.service.UserProfileService;
import com.finora.user.util.EkycTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hai endpoint mới của người dùng: gửi duyệt tay khi AI lỗi ở bước thẻ và rút lại hồ sơ chờ duyệt. */
@ExtendWith(MockitoExtension.class)
class UserProfileControllerEkycTest {

    @Mock private UserProfileService userProfileService;
    @Mock private EkycVerificationService ekycVerificationService;
    @Mock private EkycReviewWithdrawalService ekycReviewWithdrawalService;
    @Mock private KycSummaryService kycSummaryService;
    @Mock private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new UserProfileController(userProfileService, ekycVerificationService,
                ekycReviewWithdrawalService, kycSummaryService, authService));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void manualReviewReturnsDraftReadyInManualMode() throws Exception {
        authenticateWith("user:cccd:scan");
        when(ekycVerificationService.startManualReview(eq(USER_ID), any())).thenReturn(new EkycResultResponse(
                EkycStatus.PENDING, EkycResultCode.DRAFT_READY, "m", List.of(), null, true, List.of(), null,
                EkycNextStep.LIVENESS, null, null, false, true));

        mockMvc.perform(post("/api/v1/users/profile/ekyc-manual-review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cccdFrontBase64\":\"" + EkycTestData.JPEG_BASE64 + "\",\"cccdBackBase64\":\""
                                + EkycTestData.JPEG_BASE64 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCode").value("DRAFT_READY"))
                .andExpect(jsonPath("$.data.nextStep").value("LIVENESS"))
                .andExpect(jsonPath("$.data.manualReviewMode").value(true))
                .andExpect(jsonPath("$.data.manualReviewAvailable").value(false));

        ArgumentCaptor<EkycVerifyRequest> request = ArgumentCaptor.forClass(EkycVerifyRequest.class);
        verify(ekycVerificationService).startManualReview(eq(USER_ID), request.capture());
        assertThat(request.getValue().cccdFrontBase64()).isEqualTo(EkycTestData.JPEG_BASE64);
    }

    @Test
    void manualReviewWithoutImagesIsValidationError() throws Exception {
        authenticateWith("user:cccd:scan");

        mockMvc.perform(post("/api/v1/users/profile/ekyc-manual-review").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ekycVerificationService);
    }

    @Test
    void withdrawReturnsNewStatus() throws Exception {
        authenticateWith("user:cccd:scan");
        when(ekycReviewWithdrawalService.withdraw(USER_ID)).thenReturn(new MyEkycStatusResponse(EkycStatus.PENDING,
                "WITHDRAWN", Instant.parse("2026-11-17T02:00:00Z"), Instant.parse("2026-11-17T03:00:00Z"), null));

        mockMvc.perform(post("/api/v1/users/profile/ekyc-withdraw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ekycStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.latestAttemptStatus").value("WITHDRAWN"));
    }

    @Test
    void withdrawLosingRaceWithStaffIsConflict() throws Exception {
        authenticateWith("user:cccd:scan");
        when(ekycReviewWithdrawalService.withdraw(USER_ID)).thenThrow(new BusinessException(HttpStatus.CONFLICT,
                "ATTEMPT_ALREADY_DECIDED", "Hồ sơ vừa được nhân viên xử lý, vui lòng tải lại để xem kết quả."));

        mockMvc.perform(post("/api/v1/users/profile/ekyc-withdraw"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTEMPT_ALREADY_DECIDED"));
    }

    @Test
    void withdrawNeedsEkycAuthority() throws Exception {
        authenticateWith("user:profile:read");

        mockMvc.perform(post("/api/v1/users/profile/ekyc-withdraw")).andExpect(status().isForbidden());
        verifyNoInteractions(ekycReviewWithdrawalService);
    }

    @Test
    void confirmPassesReportIncorrectFlag() throws Exception {
        authenticateWith("user:cccd:scan");
        when(ekycVerificationService.confirm(eq(USER_ID), isNull(), eq(true))).thenReturn(new EkycResultResponse(
                EkycStatus.MANUAL_REVIEW, EkycResultCode.REVIEW_PENDING, "m", List.of(), null, true, List.of(), null,
                EkycNextStep.NONE, null, null, false, false));

        mockMvc.perform(post("/api/v1/users/profile/ekyc-confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportIncorrect\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultCode").value("REVIEW_PENDING"));

        verify(ekycVerificationService).confirm(USER_ID, null, true);
    }

    @Test
    void galleryImageSourceIsValidationError() throws Exception {
        authenticateWith("user:cccd:scan");

        mockMvc.perform(post("/api/v1/users/profile/ekyc-verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cccdFrontBase64\":\"" + EkycTestData.JPEG_BASE64 + "\",\"cccdBackBase64\":\""
                                + EkycTestData.JPEG_BASE64 + "\",\"frontSource\":\"GALLERY\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ekycVerificationService);
    }
}
