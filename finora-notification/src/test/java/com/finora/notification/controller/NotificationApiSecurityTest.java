package com.finora.notification.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.DualBearerTokenResolver;
import com.finora.common.security.KeycloakJwtAuthenticationConverter;
import com.finora.notification.config.NotificationSecurityConfig;
import com.finora.notification.domain.DevicePlatform;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.PushDeviceStatus;
import com.finora.notification.dto.request.UpdateNotificationPreferencesRequest;
import com.finora.notification.dto.response.NotificationPreferencesResponse;
import com.finora.notification.dto.response.PushDeliveryAdminResponse;
import com.finora.notification.dto.response.PushDeviceResponse;
import com.finora.notification.service.InAppNotificationQueryService;
import com.finora.notification.service.operations.NotificationOperationsService;
import com.finora.notification.service.preference.NotificationPreferenceService;
import com.finora.notification.service.push.PushDeviceService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API R7 dùng JWT người dùng: người sở hữu thiết bị/tùy chọn luôn lấy từ claim {@code user_id}, không từ body; thao tác
 * vận hành chỉ cho ADMIN; lỗi validation không lặp lại push token.
 */
@WebMvcTest(controllers = {InAppNotificationController.class, PushDeviceController.class,
        NotificationPreferenceController.class, NotificationOperationsController.class})
@Import({NotificationSecurityConfig.class, DualBearerTokenResolver.class, KeycloakJwtAuthenticationConverter.class})
class NotificationApiSecurityTest {

    private static final String TOKEN = "ExponentPushToken[FAKE-test-token-0001]";

    @Autowired private MockMvc mvc;
    @MockBean private InAppNotificationQueryService inApp;
    @MockBean private PushDeviceService devices;
    @MockBean private NotificationPreferenceService preferences;
    @MockBean private NotificationOperationsService operations;
    @MockBean private JwtDecoder jwtDecoder;

    @Test
    void anonymousCallerCannotReadInboxOrRegisterDevices() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/devices").contentType(MediaType.APPLICATION_JSON)
                .content(deviceBody(TOKEN))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());

        verify(devices, never()).register(anyString(), anyString(), any(), any());
    }

    @Test
    void deviceIsRegisteredForTheTokenOwnerNotForAnIdInTheBody() throws Exception {
        when(devices.register(eq("42"), eq(TOKEN), eq(DevicePlatform.ANDROID), eq("1.0.0"))).thenReturn(
                new PushDeviceResponse(UUID.randomUUID(), PushDeviceStatus.ACTIVE, DevicePlatform.ANDROID,
                        "abc123def456", Instant.parse("2026-10-17T03:00:00Z")));

        mvc.perform(post("/api/v1/notifications/devices").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\",\"platform\":\"ANDROID\",\"appVersion\":\"1.0.0\","
                                + "\"userId\":\"999\"}")
                        .with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenRef").value("abc123def456"))
                .andExpect(content().string(not(containsString(TOKEN))));

        verify(devices).register("42", TOKEN, DevicePlatform.ANDROID, "1.0.0");
    }

    @Test
    void malformedTokenIsRejectedWithoutEchoingIt() throws Exception {
        String bad = "not-an-expo-token-SECRETISH";

        mvc.perform(post("/api/v1/notifications/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(deviceBody(bad)).with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(content().string(not(containsString("SECRETISH"))));

        verify(devices, never()).register(anyString(), anyString(), any(), any());
    }

    @Test
    void logoutUnregistersOnlyWithTheCallersIdentity() throws Exception {
        UUID deviceId = UUID.randomUUID();

        mvc.perform(delete("/api/v1/notifications/devices/" + deviceId).with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isNoContent());

        verify(devices).unregister("42", deviceId);
    }

    @Test
    void preferencesAreReadAndWrittenForTheCaller() throws Exception {
        NotificationPreferencesResponse response = new NotificationPreferencesResponse(List.of(
                new NotificationPreferencesResponse.Item(NotificationCategory.DELINQUENCY, false, true)));
        when(preferences.get("42")).thenReturn(response);
        when(preferences.update(eq("42"), any(UpdateNotificationPreferencesRequest.class))).thenReturn(response);

        mvc.perform(get("/api/v1/notifications/preferences").with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].category").value("DELINQUENCY"))
                .andExpect(jsonPath("$.categories[0].inAppEnabled").value(true));
        mvc.perform(put("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":[{\"category\":\"DELINQUENCY\",\"pushEnabled\":false}]}")
                        .with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isOk());

        verify(preferences).update(eq("42"), any(UpdateNotificationPreferencesRequest.class));
    }

    @Test
    void unknownOrEmptyPreferenceIsRejected() throws Exception {
        mvc.perform(put("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":[{\"category\":\"MARKETING\",\"pushEnabled\":true}]}")
                        .with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/notifications/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferences\":[]}")
                        .with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isBadRequest());

        verify(preferences, never()).update(anyString(), any());
    }

    @Test
    void readAllReturnsTheNumberOfUpdatedNotifications() throws Exception {
        when(inApp.markAllRead()).thenReturn(3);

        mvc.perform(post("/api/v1/notifications/read-all").with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(3));
    }

    @Test
    void operationsEndpointsAreForAdminsOnly() throws Exception {
        when(operations.pushDeliveries(any(), anyInt(), anyInt())).thenReturn(
                PageResponse.<PushDeliveryAdminResponse>builder().content(List.of()).page(0).size(20)
                        .totalElements(0).totalPages(0).last(true).build());

        mvc.perform(get("/api/v1/notifications/admin/push-deliveries").with(user("42", "ROLE_BORROWER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/admin/push-deliveries/7/requeue").with(user("42", "ROLE_INVESTOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications/admin/push-deliveries").with(user("1", "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        verify(operations, never()).requeue(any(), anyString());
    }

    private static String deviceBody(String token) {
        return "{\"token\":\"" + token + "\",\"platform\":\"IOS\",\"appVersion\":\"1.0.0\"}";
    }

    private static RequestPostProcessor user(String userId, String role) {
        return jwt().jwt(token -> token.claim("user_id", userId)).authorities(new SimpleGrantedAuthority(role));
    }
}
