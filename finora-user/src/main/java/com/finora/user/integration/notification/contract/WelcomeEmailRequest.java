package com.finora.user.integration.notification.contract;

/**
 * Request gửi email chào mừng người dùng mới — contract với finora-notification.
 */
public record WelcomeEmailRequest(String email, String fullName) {
}
