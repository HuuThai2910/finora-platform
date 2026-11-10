package com.finora.user.controller;

import com.finora.user.domain.entity.UserProfile;
import com.finora.user.repository.UserFullNameRow;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void serviceAccountGetsFullNamesInOneCall() throws Exception {
        authenticateWith(AUTHORITY);
        when(userProfileRepository.findFullNamesByIdIn(List.of(42L, 43L)))
                .thenReturn(List.of(new UserFullNameRow(42L, UUID.randomUUID(), "Nguyễn Văn An")));

        mockMvc.perform(get("/api/v1/internal/users/full-names").param("userIds", "42", "43"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value("42"))
                .andExpect(jsonPath("$[0].fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$[0].maskedIdNumber").doesNotExist());
    }

    @Test
    void fullNamesNeedTheInternalAuthority() throws Exception {
        authenticateWith("user:admin:read_all");

        mockMvc.perform(get("/api/v1/internal/users/full-names").param("userIds", "42"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    void serviceAccountGetsIdsByName() throws Exception {
        authenticateWith(AUTHORITY);
        UUID keycloakId = UUID.randomUUID();
        when(userProfileRepository.findFullNamesByNameContaining(any(), any())).thenCallRealMethod();
        when(userProfileRepository.findFullNamesByPlainNameLike(any(), any(), any(), any()))
                .thenReturn(List.of(new UserFullNameRow(42L, keycloakId, "Nguyễn Văn An")));

        mockMvc.perform(get("/api/v1/internal/users/ids-by-name").param("q", "Văn An"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value("42"))
                .andExpect(jsonPath("$[0].keycloakUserId").value(keycloakId.toString()))
                .andExpect(jsonPath("$[0].fullName").doesNotExist());
    }

    @Test
    void idsByNameNeedTheInternalAuthorityAndTwoCharacters() throws Exception {
        authenticateWith("user:admin:read_all");
        mockMvc.perform(get("/api/v1/internal/users/ids-by-name").param("q", "An"))
                .andExpect(status().isForbidden());

        // Một ký tự quá chung: không tra (Spring chặn 400 bằng @Size; service cũng tự bỏ qua khi gọi thẳng).
        authenticateWith(AUTHORITY);
        mockMvc.perform(get("/api/v1/internal/users/ids-by-name").param("q", " A "))
                .andExpect(jsonPath("$.length()").value(0));
        verifyNoInteractions(userProfileRepository);
    }
}
