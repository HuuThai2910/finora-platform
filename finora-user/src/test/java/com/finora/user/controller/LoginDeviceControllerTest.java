package com.finora.user.controller;

import com.finora.user.dto.response.LoginDeviceResponse;
import com.finora.user.service.LoginDeviceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Danh sách thiết bị luôn theo người gọi trong token; API cho nhân viên chỉ mở với quyền Xem khách hàng. */
class LoginDeviceControllerTest {

    private final LoginDeviceService service = mock(LoginDeviceService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new LoginDeviceController(service), new AdminLoginDeviceController(service));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void myDevicesUsesTheCallerAndItsDeviceId() throws Exception {
        authenticateWith("ROLE_USER");
        when(service.myDevices(eq(USER_ID), eq("device-0001"), isNull())).thenReturn(List.of(new LoginDeviceResponse(
                1L, "iPhone 15 Pro", "iOS 18.1", "MOBILE", "1.4.0", "113.161.*.*", Instant.parse("2026-11-01T03:00:00Z"),
                Instant.parse("2026-11-09T03:00:00Z"), 5, true, false)));

        mockMvc.perform(get("/api/v1/users/me/devices").header("X-Device-Id", "device-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceName").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$[0].current").value(true))
                .andExpect(jsonPath("$[0].deviceId").doesNotExist());
    }

    @Test
    void signOutPassesCallerAndCurrentDevice() throws Exception {
        authenticateWith("ROLE_USER");

        mockMvc.perform(delete("/api/v1/users/me/devices/7").header("X-Device-Id", "device-0001"))
                .andExpect(status().isNoContent());

        verify(service).signOut(eq(USER_ID), eq(7L), eq("device-0001"), isNull());
    }

    @Test
    void staffWithCustomerViewReadsCustomerDevices() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:customer:view");
        when(service.devicesOf(42L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/admin/users/42/devices")).andExpect(status().isOk());
    }

    @Test
    void ordinaryUserCannotReadSomeoneElsesDevices() throws Exception {
        authenticateWith("ROLE_USER");

        mockMvc.perform(get("/api/v1/admin/users/42/devices")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
