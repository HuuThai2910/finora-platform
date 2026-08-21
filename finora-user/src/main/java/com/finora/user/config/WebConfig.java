package com.finora.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cấu hình CORS dự phòng ở tầng MVC — bổ sung cho SecurityConfig.corsConfigurationSource().
 * Đảm bảo CORS hoạt động đúng khi request không đi qua security filter chain
 * (ví dụ: error endpoint, static resource).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "http://localhost:3000",   // Next.js / React dev server
                        "http://localhost:5173",   // Vite dev server
                        "http://localhost:8080"     // API Gateway
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
