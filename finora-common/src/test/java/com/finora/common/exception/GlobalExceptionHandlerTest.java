package com.finora.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorTestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void validationErrorUsesStableEnvelopeWithoutRejectedValue() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.details.fieldErrors[0].reason").value("Tên không được để trống"))
                .andExpect(jsonPath("$.traceId", not(blankOrNullString())));
    }

    @Test
    void malformedJsonDoesNotExposeParserDetails() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("JsonParseException"))));
    }

    @Test
    void businessExceptionKeepsMachineCodeAndHttpStatus() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_STATE_INVALID"))
                .andExpect(jsonPath("$.message").value("Trạng thái khoản vay không hợp lệ"));
    }

    @Test
    void conflictUsesSameErrorEnvelope() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.traceId", not(blankOrNullString())));
    }

    @Test
    void notFoundUsesSameErrorEnvelope() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId", not(blankOrNullString())));
    }

    @Test
    void unexpectedErrorHidesInternalMessage() throws Exception {
        mockMvc.perform(get("/test/general"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("database-password-leak"))));
    }

    @RestController
    static class ErrorTestController {

        @PostMapping("/test/validation")
        String validate(@Valid @RequestBody SampleRequest request) {
            return request.name();
        }

        @GetMapping("/test/business")
        String businessError() {
            throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "LOAN_STATE_INVALID",
                    "Trạng thái khoản vay không hợp lệ"
            );
        }

        @GetMapping("/test/general")
        String generalError() {
            throw new IllegalStateException("database-password-leak");
        }

        @GetMapping("/test/conflict")
        String conflict() {
            throw new BusinessException(HttpStatus.CONFLICT, "CONFLICT", "Dữ liệu đã được thay đổi");
        }

        @GetMapping("/test/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Không tìm thấy dữ liệu kiểm thử");
        }
    }

    record SampleRequest(@NotBlank(message = "Tên không được để trống") String name) {
    }
}
