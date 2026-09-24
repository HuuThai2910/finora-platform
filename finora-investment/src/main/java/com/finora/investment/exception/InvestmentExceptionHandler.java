package com.finora.investment.exception;

import com.finora.common.dto.ApiErrorResponse;
import com.finora.common.logging.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Chuyển lỗi domain của Investment thành response chuẩn
 * {@code {code, message, details, traceId}}.
 *
 * <p>Domain không biết HTTP; ánh xạ sang status nằm ở đây (04-conventions-contracts.md).
 * Message của {@link InvestmentDomainException} được viết sẵn cho người dùng đọc nên trả
 * thẳng ra ngoài; không có stack trace hoặc thông tin database nào lọt ra.</p>
 *
 * <p>Ưu tiên cao hơn {@code GlobalExceptionHandler} của finora-common để handler riêng của
 * module được chọn trước cho đúng loại exception này.</p>
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvestmentExceptionHandler {

    @ExceptionHandler(InvestmentDomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomain(InvestmentDomainException e) {
        HttpStatus status = switch (e.getKind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };

        String traceId = TraceContext.currentTraceIdOrCreate();
        log.info("Lỗi nghiệp vụ Investment: code={}, status={}, traceId={}",
                e.getCode(), status.value(), traceId);

        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(e.getCode(), e.getMessage(), null, traceId));
    }
}
