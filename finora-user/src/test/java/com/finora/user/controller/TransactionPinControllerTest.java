package com.finora.user.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.user.dto.response.PinStatusResponse;
import com.finora.user.dto.response.PinTokenResponse;
import com.finora.user.service.TransactionPinService;
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

import static com.finora.user.controller.ControllerTestSupport.USER_ID;
import static com.finora.user.controller.ControllerTestSupport.authenticateWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Biên HTTP của mã PIN giao dịch: tạo PIN trả 201, các thao tác khác trả 200, JSON không bọc {@code BaseResponse}. */
@ExtendWith(MockitoExtension.class)
class TransactionPinControllerTest {

    private static final String BASE = "/api/v1/users/me/pin";

    @Mock
    private TransactionPinService transactionPinService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = ControllerTestSupport.mockMvc(new TransactionPinController(transactionPinService));
        authenticateWith();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createReturnsCreatedWithPinStatus() throws Exception {
        when(transactionPinService.create(USER_ID, "123456")).thenReturn(new PinStatusResponse(true, false, null, 5));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasPin").value(true))
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.remainingAttempts").value(5));
    }

    @Test
    void statusVerifyChangeAndResetReturnOk() throws Exception {
        PinStatusResponse pinStatus = new PinStatusResponse(true, false, null, 5);
        when(transactionPinService.status(USER_ID)).thenReturn(pinStatus);
        when(transactionPinService.verify(eq(USER_ID), eq("123456"), eq(PinScope.INVEST), eq("idem-key-123"), any()))
                .thenReturn(new PinTokenResponse("pin-token", Instant.parse("2026-11-04T00:03:00Z")));
        when(transactionPinService.change(eq(USER_ID), eq("123456"), eq("654321"), any())).thenReturn(pinStatus);
        when(transactionPinService.reset(eq(USER_ID), eq("Finora@12345"), eq("654321"), any())).thenReturn(pinStatus);

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPin").value(true));
        mockMvc.perform(post(BASE + "/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"123456\",\"scope\":\"INVEST\",\"requestKey\":\"idem-key-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinToken").value("pin-token"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-04T00:03:00Z"));
        mockMvc.perform(put(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPin\":\"123456\",\"newPin\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingAttempts").value(5));
        mockMvc.perform(post(BASE + "/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Finora@12345\",\"newPin\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPin").value(true));
    }

    @Test
    void malformedPinIsRejectedBeforeService() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"12ab\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transactionPinService);
    }
}
