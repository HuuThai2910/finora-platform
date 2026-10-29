package com.finora.user.controller;

import com.finora.user.domain.UserProfile;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.UserIdentityService;
import com.finora.user.service.impl.UserIdentityServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Biên HTTP của {@code /internal/users/{userId}/identity}: quyền service account, CCCD đã che, 404 người lạ. */
@ExtendWith(MockitoExtension.class)
class InternalUserControllerTest {

    private static final String AUTHORITY = "user:identity:read_internal";

    @Mock
    private UserProfileRepository userProfileRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(
                new InternalUserController(new UserIdentityServiceImpl(userProfileRepository)));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void serviceAccountGetsFullNameAndMaskedIdNumber() throws Exception {
        authenticateWith(AUTHORITY);
        when(userProfileRepository.findById(42L)).thenReturn(Optional.of(UserProfile.builder()
                .id(42L)
                .keycloakUserId(UUID.randomUUID())
                .email("an@example.test")
                .fullName("Nguyễn Văn An")
                .idNumberEncrypted("079203012345")
                .build()));

        mockMvc.perform(get("/api/v1/internal/users/42/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("42"))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.maskedIdNumber").value("********2345"))
                .andExpect(content().string(not(containsString("079203012345"))))
                .andExpect(content().string(not(containsString("an@example.test"))));
    }

    @Test
    void userWithoutCccdHasNullMaskedIdNumber() throws Exception {
        authenticateWith(AUTHORITY);
        when(userProfileRepository.findById(43L)).thenReturn(Optional.of(UserProfile.builder()
                .id(43L).keycloakUserId(UUID.randomUUID()).email("binh@example.test").build()));

        mockMvc.perform(get("/api/v1/internal/users/43/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("43"))
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.maskedIdNumber").doesNotExist());
    }

    @Test
    void userTokenWithoutInternalAuthorityGets403() throws Exception {
        authenticateWith("user:profile:read", "user:admin:read_all");

        mockMvc.perform(get("/api/v1/internal/users/42/identity"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    void unknownUserGets404WithStandardError() throws Exception {
        authenticateWith(AUTHORITY);
        when(userProfileRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/internal/users/999/identity"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
