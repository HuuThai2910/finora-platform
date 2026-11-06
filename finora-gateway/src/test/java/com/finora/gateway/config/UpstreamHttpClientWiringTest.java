package com.finora.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Gateway MVC phải dùng client HTTP/1.1 (Apache HttpClient) của FINORA thay cho bean mặc định JDK HTTP/2 (B43). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UpstreamHttpClientWiringTest {

    @Autowired
    private ClientHttpRequestFactory requestFactory;

    @Test
    void gatewayProxiesThroughTheApacheHttp11Client() {
        assertThat(requestFactory).isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    }
}
