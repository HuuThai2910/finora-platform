package com.finora.user.controller;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.finora.common.exception.GlobalExceptionHandler;
import com.finora.user.exception.SecurityExceptionHandler;
import com.finora.user.exception.UploadExceptionHandler;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Arrays;
import java.util.UUID;

/**
 * Dựng MockMvc độc lập cho controller eKYC: bọc controller bằng đúng interceptor {@code @PreAuthorize}
 * và gắn các advice lỗi thật của module, không dựng Spring context (không cần Keycloak, Redis, database).
 */
final class ControllerTestSupport {

    static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ControllerTestSupport() {
    }

    static MockMvc mockMvc(Object... controllers) {
        Object[] proxies = Arrays.stream(controllers).map(ControllerTestSupport::secured).toArray();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(proxies)
                .setControllerAdvice(new UploadExceptionHandler(), new SecurityExceptionHandler(),
                        new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(
                        new ByteArrayHttpMessageConverter(),
                        new StringHttpMessageConverter(),
                        new ResourceHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    /** Đăng nhập bằng JWT giả có {@code sub} là UUID và các quyền chỉ định. */
    static void authenticateWith(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .claim("email", "nguoidung@example.test")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static Object secured(Object controller) {
        ProxyFactory proxyFactory = new ProxyFactory(controller);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return proxyFactory.getProxy();
    }
}
