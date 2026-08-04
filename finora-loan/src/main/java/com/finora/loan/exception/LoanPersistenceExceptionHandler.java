package com.finora.loan.exception;

import com.finora.common.dto.ApiErrorResponse;
import com.finora.common.logging.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.finora.loan")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class LoanPersistenceExceptionHandler {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> optimisticLock(ObjectOptimisticLockingFailureException exception) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Xung đột optimistic lock trong Loan: entityType={}, traceId={}",
                exception.getPersistentClassName(), traceId);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(TraceContext.HEADER_NAME, traceId)
                .body(new ApiErrorResponse(
                        "CONCURRENT_UPDATE_CONFLICT",
                        "Dữ liệu đã được cập nhật bởi yêu cầu khác, vui lòng tải lại",
                        null,
                        traceId
                ));
    }
}
