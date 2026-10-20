package com.finora.common.security.pin;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Chặn endpoint {@link RequirePin} khi request thiếu pin-token hợp lệ cho đúng thao tác.
 * <p>
 * Chạy sau Spring Security nên JWT đã được xác thực; lỗi ném ở đây đi qua
 * {@code GlobalExceptionHandler} như mọi lỗi nghiệp vụ khác. Thao tác cần PIN bắt buộc có
 * {@code Idempotency-Key}: token chỉ hợp lệ cho đúng khóa đó (D38).
 */
@RequiredArgsConstructor
public class RequirePinInterceptor implements HandlerInterceptor {

    private final PinTokenService pinTokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePin requirePin = method.getMethodAnnotation(RequirePin.class);
        if (requirePin == null) {
            return true;
        }
        String token = request.getHeader(PinTokenService.HEADER_NAME);
        if (token == null || token.isBlank()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PIN_REQUIRED",
                    "Thao tác này cần xác nhận bằng mã PIN");
        }
        String requestKey = request.getHeader(PinTokenService.REQUEST_KEY_HEADER);
        if (requestKey == null || requestKey.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Thao tác cần xác nhận mã PIN phải gửi kèm Idempotency-Key");
        }
        pinTokenService.verify(token.trim(), SecurityUtils.getJwt().getSubject(), requirePin.value(), requestKey);
        return true;
    }
}
