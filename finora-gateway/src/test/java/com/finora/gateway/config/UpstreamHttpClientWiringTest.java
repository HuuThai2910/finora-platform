package com.finora.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Gateway MVC phải dùng client HTTP/1.1 của FINORA thay cho bean mặc định HTTP/2 (B43). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UpstreamHttpClientWiringTest {

    @Autowired
    private ClientHttpRequestFactory requestFactory;

    @Test
    void gatewayProxiesThroughTheHttp11Client() {
        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
        HttpClient client = (HttpClient) ReflectionTestUtils.getField(requestFactory, "httpClient");
        assertThat(client).isNotNull();
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }
}
