package com.finora.user.controller;

import com.finora.common.dto.PageResponse;
import com.finora.user.dto.response.EkycAttemptDetailResponse;
import com.finora.user.dto.response.EkycAttemptSummaryResponse;
import com.finora.user.integration.storage.StoredObject;
import com.finora.user.repository.UserStatisticsRepository;
import com.finora.user.service.EkycAdminReviewService;
import com.finora.user.service.UserProfileService;
import com.finora.user.service.UserStatisticsService;
import com.finora.user.service.impl.UserStatisticsServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trang Duyệt eKYC dùng chung tiền tố với {@link AdminUserController}: đường literal
 * {@code /ekyc-attempts} phải thắng mẫu {@code /{id}}, quyền đọc và quyền quyết định tách riêng.
 */
@ExtendWith(MockitoExtension.class)
class AdminEkycAttemptControllerTest {

    private static final String BASE = "/api/v1/admin/users/ekyc-attempts";
    private static final UUID ATTEMPT = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock private EkycAdminReviewService reviewService;
    @Mock private UserProfileService userProfileService;
    @Mock private UserStatisticsRepository statisticsRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(
                new AdminUserController(userProfileService, new UserStatisticsServiceImpl(statisticsRepository, Clock.systemUTC())),
                new AdminEkycAttemptController(reviewService));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void literalPathWinsOverAdminUserIdPattern() throws Exception {
        authenticateWith("user:admin:read_all");
        when(reviewService.list(null, 0, 20)).thenReturn(PageResponse.<EkycAttemptSummaryResponse>builder()
                .content(List.of(new EkycAttemptSummaryResponse(ATTEMPT, 7L, "nguoidung@example.test", "MANUAL_REVIEW",
                        "CCCD_CHIP_2021", "NEEDS_REVIEW", "REVIEW", 0.41, "GRAY", List.of("QR_MRZ_AGREE"),
                        Instant.parse("2026-10-16T03:00:00Z"), Instant.parse("2026-10-16T03:05:00Z"))))
                .page(0).size(20).totalElements(1).totalPages(1).last(true).build());

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attemptId").value(ATTEMPT.toString()))
                .andExpect(jsonPath("$.content[0].faceMatchZone").value("GRAY"))
                .andExpect(jsonPath("$.content[0].submittedAt").value("2026-10-16T03:05:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true));
        verifyNoInteractions(userProfileService);
    }

    @Test
    void numericUserIdStillRoutesToAdminUserController() throws Exception {
        authenticateWith("user:admin:read_all");

        mockMvc.perform(get("/api/v1/admin/users/42")).andExpect(status().isOk());

        verify(userProfileService).getUserById(42L);
        verifyNoInteractions(reviewService);
    }

    @Test
    void listPassesStatusAndPaging() throws Exception {
        authenticateWith("user:admin:read_all");
        when(reviewService.list("ALL", 2, 50)).thenReturn(PageResponse.<EkycAttemptSummaryResponse>builder()
                .content(List.of()).page(2).size(50).totalElements(0).totalPages(0).last(true).build());

        mockMvc.perform(get(BASE).param("status", "ALL").param("page", "2").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void detailUsesAttemptPath() throws Exception {
        authenticateWith("user:admin:read_all");
        when(reviewService.detail(ATTEMPT.toString(), USER_ID)).thenReturn(detail("MANUAL_REVIEW"));

        mockMvc.perform(get(BASE + "/" + ATTEMPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.livenessReasons[0]").value("FACE_MATCH_GRAY"))
                .andExpect(jsonPath("$.evidence[0].evidenceId").value(11))
                .andExpect(jsonPath("$.evidence[0].kind").value("CCCD_FRONT"));
    }

    @Test
    void evidenceIsStreamedInlineWithoutCaching() throws Exception {
        authenticateWith("user:admin:read_all");
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        when(reviewService.openEvidence(ATTEMPT.toString(), "11", USER_ID)).thenReturn(
                new EkycAdminReviewService.EvidenceDownload(
                        new StoredObject(new ByteArrayInputStream(jpeg), jpeg.length, "image/jpeg"), "image/jpeg", jpeg.length));

        mockMvc.perform(get(BASE + "/" + ATTEMPT + "/evidence/11"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg));
    }

    @Test
    void decisionsRequireLockAuthority() throws Exception {
        authenticateWith("user:admin:read_all");

        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"sai\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reviewService);
    }

    @Test
    void readingRequiresReadAuthority() throws Exception {
        authenticateWith("user:cccd:scan");

        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + ATTEMPT + "/evidence/11")).andExpect(status().isForbidden());
        verifyNoInteractions(reviewService);
    }

    @Test
    void approveWithLockAuthorityAndOptionalBody() throws Exception {
        authenticateWith("user:admin:lock");
        when(reviewService.approve(eq(ATTEMPT.toString()), eq(USER_ID), any())).thenReturn(detail("APPROVED"));

        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/approve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"khớp ảnh\"}"))
                .andExpect(status().isOk());
        verify(reviewService).approve(ATTEMPT.toString(), USER_ID, "khớp ảnh");
    }

    @Test
    void staffWithEkycActionCanDecide() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:ekyc:view", "ops:ekyc:action");
        when(reviewService.approve(eq(ATTEMPT.toString()), eq(USER_ID), any())).thenReturn(detail("APPROVED"));
        when(reviewService.reject(ATTEMPT.toString(), USER_ID, "ảnh mờ")).thenReturn(detail("REJECTED"));

        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ảnh mờ\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void staffWithOnlyEkycViewCanReadButNotDecide() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:ekyc:view");
        when(reviewService.list(null, 0, 20)).thenReturn(PageResponse.<EkycAttemptSummaryResponse>builder()
                .content(List.of()).page(0).size(20).totalElements(0).totalPages(0).last(true).build());

        mockMvc.perform(get(BASE)).andExpect(status().isOk());
        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"sai\"}"))
                .andExpect(status().isForbidden());
        verify(reviewService, org.mockito.Mockito.never()).approve(anyString(), any(), any());
    }

    @Test
    void staffWithoutEkycPermissionCannotRead() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:customer:view", "ops:loan-application:action");

        mockMvc.perform(get(BASE)).andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/" + ATTEMPT)).andExpect(status().isForbidden());
        verifyNoInteractions(reviewService);
    }

    @Test
    void adminRolePassesWithoutLegacyAuthorities() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(reviewService.approve(eq(ATTEMPT.toString()), eq(USER_ID), any())).thenReturn(detail("APPROVED"));

        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/approve")).andExpect(status().isOk());
    }

    @Test
    void rejectWithoutNoteIsValidationError() throws Exception {
        authenticateWith("user:admin:lock");

        mockMvc.perform(post(BASE + "/" + ATTEMPT + "/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verify(reviewService, org.mockito.Mockito.never()).reject(anyString(), any(), anyString());
    }

    private static EkycAttemptDetailResponse detail(String status) {
        return new EkycAttemptDetailResponse(ATTEMPT, 7L, "nguoidung@example.test", status, "CCCD_CHIP_2021",
                "NEEDS_REVIEW", "REVIEW", 0.41, "GRAY", List.of("QR_MRZ_AGREE"), Instant.parse("2026-10-16T03:00:00Z"),
                Instant.parse("2026-10-16T03:05:00Z"), null, List.of("ACTIONS_MATCH", "FACE_MATCH_GRAY").subList(1, 2),
                "ekyc-2026-10",
                List.of(new EkycAttemptDetailResponse.EvidenceItem(11L, "CCCD_FRONT", null, "image/jpeg", 1234L, false)),
                null, null, null, null, null);
    }
}
