package com.finora.common.exception;

import com.finora.common.dto.ApiErrorResponse;
import com.finora.common.dto.FieldViolation;
import com.finora.common.logging.TraceContext;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Xử lý lỗi toàn cục cho TẤT CẢ các service trong hệ thống FINORA.
 * Mỗi service chỉ cần import finora-common là tự động được bảo vệ.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Yêu cầu bị từ chối bởi quy tắc nghiệp vụ: code={}, status={}, traceId={}",
                ex.getCode(), ex.getStatus().value(), traceId);
        return error(ex.getStatus(), ex.getCode(), ex.getMessage(), null, traceId);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Không tìm thấy tài nguyên: code=RESOURCE_NOT_FOUND, traceId={}", traceId);
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), null, traceId);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Không tìm thấy route hoặc tài nguyên HTTP: code=RESOURCE_NOT_FOUND, traceId={}", traceId);
        return error(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Không tìm thấy tài nguyên được yêu cầu",
                null,
                traceId
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), safeReason(error.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::reason))
                .toList();
        return validationError(violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        violation.getPropertyPath().toString(),
                        safeReason(violation.getMessage())
                ))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::reason))
                .toList();
        return validationError(violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedRequest(HttpMessageNotReadableException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Request JSON không đọc được: code=MALFORMED_REQUEST, traceId={}", traceId);
        return error(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "Dữ liệu gửi lên không đúng định dạng",
                null,
                traceId
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneral(Exception ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        // Không log message/stack ở baseline chung vì exception ngoài kiểm soát có thể chứa SQL hoặc PII.
        log.error("Lỗi hệ thống chưa được xử lý: exceptionType={}, traceId={}",
                ex.getClass().getName(), traceId);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Lỗi hệ thống, vui lòng thử lại sau",
                null,
                traceId
        );
    }

    private ResponseEntity<ApiErrorResponse> validationError(List<FieldViolation> violations) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Request không đạt validation: fieldErrorCount={}, traceId={}", violations.size(), traceId);
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Dữ liệu không hợp lệ",
                Map.of("fieldErrors", violations),
                traceId
        );
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            Object details,
            String traceId
    ) {
        return ResponseEntity.status(status)
                .header(TraceContext.HEADER_NAME, traceId)
                .body(new ApiErrorResponse(code, message, details, traceId));
    }

    private String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "Giá trị không hợp lệ" : reason;
    }
}
