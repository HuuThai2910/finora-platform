package com.finora.user.controller;

import com.finora.common.enums.user.UserRole;
import com.finora.user.dto.request.CreateStaffAccountRequest;
import com.finora.user.dto.response.StaffAccountCreatedResponse;
import com.finora.user.service.StaffAccountService;
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

import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/admin/users}: chỉ ADMIN tạo được tài khoản nội bộ; mật khẩu tạm trả trong body, cấm cache.
 */
@ExtendWith(MockitoExtension.class)
class AdminStaffAccountControllerTest {

    private static final String BASE = "/api/v1/admin/users";
    private static final String BODY = """
            {"fullName":"Lê Văn Nam","email":"nv.nam@finora.vn","phone":"0912345678",
             "role":"STAFF","staffRoleCode":"STAFF_THU_HOI_NO"}""";

    @Mock private StaffAccountService staffAccountService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new AdminStaffAccountController(staffAccountService));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminCreatesStaffAndReceivesTemporaryPasswordOnce() throws Exception {
        authenticateWith("ROLE_ADMIN");
        when(staffAccountService.createStaffAccount(any())).thenReturn(new StaffAccountCreatedResponse(
                77L, "nv.nam@finora.vn", "Lê Văn Nam", UserRole.STAFF, "STAFF_THU_HOI_NO", "Thu hồi nợ",
                "Ab3$kLmnPqRs7x", true));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(77))
                .andExpect(jsonPath("$.temporaryPassword").value("Ab3$kLmnPqRs7x"))
                .andExpect(jsonPath("$.credentialsEmailSent").value(true));

        ArgumentCaptor<CreateStaffAccountRequest> request = ArgumentCaptor.forClass(CreateStaffAccountRequest.class);
        verify(staffAccountService).createStaffAccount(request.capture());
        assertThat(request.getValue().getStaffRoleCode()).isEqualTo("STAFF_THU_HOI_NO");
    }

    @Test
    void staffEvenWithCustomerActionCannotCreateAccounts() throws Exception {
        authenticateWith("ROLE_STAFF", "ops:customer:view", "ops:customer:action");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(staffAccountService);
    }

    @Test
    void missingNameOrBadPhoneIsBadRequest() throws Exception {
        authenticateWith("ROLE_ADMIN");

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@finora.vn\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"A\",\"email\":\"a@finora.vn\",\"phone\":\"12345\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(staffAccountService);
    }
}
