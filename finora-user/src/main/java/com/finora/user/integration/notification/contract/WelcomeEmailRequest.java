package com.finora.user.integration.notification.contract;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi email chào mừng người dùng mới — contract với finora-notification.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WelcomeEmailRequest {

    private String email;
    private String fullName;
}
