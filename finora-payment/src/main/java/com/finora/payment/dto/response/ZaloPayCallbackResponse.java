package com.finora.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ZaloPayCallbackResponse(
        @JsonProperty("return_code") int returnCode,
        @JsonProperty("return_message") String returnMessage
) {}
