package com.finora.blockchain.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;

/**
 * Cho probe hạ tầng gọi {@code /actuator/health/**} không cần đăng nhập (W3E, D56).
 *
 * <p>Trên nhánh gốc của W3E, Blockchain chưa có {@code SecurityFilterChain} riêng nên dùng chain mặc định của Spring Boot
 * (mọi endpoint cần đăng nhập). Khai báo một {@code SecurityFilterChain} ở đây sẽ làm Spring Boot bỏ chain mặc định và mở
 * toàn bộ API còn lại, nên chỉ bỏ qua đúng đường health; cách này cũng đúng khi đã có chain JWT của W3D
 * ({@code BlockchainSecurityConfig}). Health chỉ trả trạng thái (không chi tiết). Sau khi merge W3D, chuyển hai đường này
 * thành {@code permitAll()} trong {@code BlockchainSecurityConfig} và xóa class này.</p>
 */
@Configuration
public class BlockchainHealthEndpointSecurityConfig {

    @Bean
    WebSecurityCustomizer healthEndpointWebSecurityCustomizer() {
        return web -> web.ignoring().requestMatchers("/actuator/health", "/actuator/health/**");
    }
}
