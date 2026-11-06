package com.finora.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CapturingResponseWrapperTest {

    @Test
    void passesBodyThroughAndDecodesJsonWithoutCharsetAsUtf8() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingResponseWrapper wrapper = new CapturingResponseWrapper(response, 1024);
        wrapper.setContentType("application/json");
        byte[] body = "{\"message\":\"Số tiền vay không hợp lệ\"}".getBytes(StandardCharsets.UTF_8);

        wrapper.getOutputStream().write(body, 0, body.length);

        assertThat(response.getContentAsByteArray()).isEqualTo(body);
        assertThat(wrapper.capturedText()).isEqualTo("{\"message\":\"Số tiền vay không hợp lệ\"}");
        assertThat(wrapper.isTruncated()).isFalse();
    }

    @Test
    void keepsOnlyLimitButWritesEverything() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingResponseWrapper wrapper = new CapturingResponseWrapper(response, 4);
        byte[] body = "abcdefgh".getBytes(StandardCharsets.UTF_8);

        wrapper.getOutputStream().write(body, 0, body.length);

        assertThat(response.getContentAsString()).isEqualTo("abcdefgh");
        assertThat(wrapper.capturedText()).isEqualTo("abcd");
        assertThat(wrapper.isTruncated()).isTrue();
    }
}
