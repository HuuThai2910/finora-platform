package com.finora.gateway.filter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kiểm tra gateway chặn route eKYC nội bộ của finora-ai và {@code /api/v1/internal/**} nhưng vẫn cho route chấm điểm,
 * hồ sơ người dùng đi qua.
 */
class InternalAiRouteBlockingFilterTest {

    private final InternalAiRouteBlockingFilter filter = new InternalAiRouteBlockingFilter();

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/ai/ekyc/document",
            "/api/v1/ai/ekyc/liveness",
            "/api/v1/ai/ekyc/ocr",
            "/api/v1/ai/ekyc",
            "/api/v1/ai/ekyc/",
            "/API/V1/AI/EKYC/document",
            "/Api/v1/Ai/Ekyc/Liveness",
            "//api//v1///ai/ekyc/document",
            "/api/v1/./ai/./ekyc/document",
            "/api/v1/ai/credit/../ekyc/document",
            "/api/v1/ai/%65kyc/document",
            "/api/v1/ai/%2565kyc/document",
            "/api/v1/ai/ekyc;jsessionid=abc/document",
            "/api/v1/ai/ekyc%2Fdocument",
            // Endpoint service-to-service (danh tính cho giấy chuyển nhượng Note) không được lộ ra ngoài.
            "/api/v1/internal/users/42/identity",
            "/api/v1/internal",
            "/API/V1/Internal/users/42/identity",
            "/api/v1/users/../internal/users/42/identity",
            "/api/v1/users/%2e%2e/internal/users/42/identity",
            "/api/v1/users/%252e%252e/internal/users/42/identity"
    })
    void blocksEveryVariantOfInternalEkycRoutes(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Không tìm thấy tài nguyên\"}");
        assertThat(chain.getRequest()).as("request không được chuyển tiếp tới AI").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/ai/credit/explain",
            "/api/v1/ai/credit/score",
            "/api/v1/ai/ekycx/document",
            "/api/v1/users/profile/ekyc-verify",
            "/api/v1/users/profile/ekyc/liveness",
            "/api/v1/users/me",
            "/api/v1/internalx/users",
            "/health"
    })
    void letsOtherRoutesThrough(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("request phải đi tiếp trong chuỗi filter").isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/ai/ekyc/document", "/api/v1/AI/ekyc/liveness"})
    void blocksWhenOnlyTheServletPathRevealsTheRoute(String servletPath) throws Exception {
        // Container đã giải mã/chuẩn hoá servletPath trong khi requestURI thô trông vô hại.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/innocent");
        request.setServletPath(servletPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }
}
