package com.finora.notification.dto.request;

/**
 * Request gửi email chào mừng người dùng mới.
 */
public record WelcomeEmailRequest(String email, String fullName) {
}
