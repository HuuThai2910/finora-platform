package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record EkycVerifyRequest(
    @NotBlank String selfieBase64,
    @NotBlank String cccdImageBase64
) {}
