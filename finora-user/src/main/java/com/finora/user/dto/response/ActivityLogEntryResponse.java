package com.finora.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Một lượt gọi API qua Gateway, đọc từ dòng access log {@code finora.api} trên Azure Log Analytics.
 * Body gửi lên đã được Gateway che mật khẩu, PIN, OTP, token, ảnh trước khi ghi.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityLogEntryResponse {

    private Instant at;
    private String method;
    private String path;
    private String query;
    /** Route id của Gateway ({@code loan-service}…); {@code gateway} khi Gateway tự trả lời. */
    private String service;
    private int status;
    private long durationMs;
    private String user;
    private String role;
    /** {@code WEB}, {@code MOBILE}, {@code OTHER}. */
    private String client;
    private String ip;
    private String errorCode;
    private String errorMessage;
    private String traceId;
    private String requestBody;
}
