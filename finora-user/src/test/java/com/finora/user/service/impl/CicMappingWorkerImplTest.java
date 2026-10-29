package com.finora.user.service.impl;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.common.enums.user.EkycStatus;
import com.finora.user.config.CicMappingProperties;
import com.finora.user.domain.UserProfile;
import com.finora.user.integration.cic.client.CicMappingClient;
import com.finora.user.integration.cic.contract.RegisterBorrowerMappingRequest;
import com.finora.user.repository.UserProfileRepository;
import com.finora.user.service.CicMappingTaskService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CicMappingWorkerImplTest {
    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private CicMappingTaskService taskService;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private CicMappingClient client;

    private CicMappingProperties properties;
    private CicMappingWorkerImpl worker;

    @BeforeEach
    void setUp() {
        properties = new CicMappingProperties();
        properties.setInternalApiKey("test-internal-key");
        worker = new CicMappingWorkerImpl(taskService, userProfileRepository, client, properties);
    }

    @Test
    void guiUserIdCuaHoSoLamBorrowerIdVaCccdQuaKenhNoiBo() {
        when(taskService.claim(12L)).thenReturn(new CicMappingTaskService.ClaimedTask(12L, 7L, 1));
        when(userProfileRepository.findById(7L)).thenReturn(Optional.of(profile("036094001234")));

        worker.executeSafely(12L);

        verify(client).register(
                "test-internal-key",
                // B26: borrowerId = claim user_id (user_profiles.id = 7), cùng khóa với event Loan/Payment.
                new RegisterBorrowerMappingRequest("7", "036094001234"));
        verify(taskService).complete(12L);
    }

    @Test
    void thieuApiKeyThiKhongGuiCccdVaHenRetry() {
        properties.setInternalApiKey(" ");
        when(taskService.claim(12L)).thenReturn(new CicMappingTaskService.ClaimedTask(12L, 7L, 1));
        when(userProfileRepository.findById(7L)).thenReturn(Optional.of(profile("036094001234")));

        worker.executeSafely(12L);

        verify(client, never()).register(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(taskService).fail(12L, "CIC_UNAVAILABLE", true);
    }

    private static UserProfile profile(String cccd) {
        return UserProfile.builder()
                .id(7L)
                .keycloakUserId(USER_ID)
                .email("borrower@example.com")
                .idNumberEncrypted(cccd)
                .ekycStatus(EkycStatus.VERIFIED)
                .build();
    }
}
