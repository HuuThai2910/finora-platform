package com.finora.user.dto.response;

import com.finora.user.domain.EkycStatus;

public record EkycResultResponse(
    EkycStatus status,
    boolean faceMatch,
    double faceMatchScore,
    boolean livenessVerified,
    String message
) {}
