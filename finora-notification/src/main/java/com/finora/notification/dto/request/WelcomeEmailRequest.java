package com.finora.notification.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request gửi email chào mừng người dùng mới.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WelcomeEmailRequest {

    private String email;
    private String fullName;
}
