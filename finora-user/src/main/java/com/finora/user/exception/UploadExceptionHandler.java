package com.finora.user.exception;

import com.finora.common.dto.ApiErrorResponse;
import com.finora.common.logging.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Lỗi upload multipart (video liveness) thành đúng mã HTTP thay vì 500 chung.
 * <p>
 * {@code GlobalExceptionHandler} dùng chung có {@code @ExceptionHandler(Exception.class)}; nếu advice này
 * không được hỏi trước ({@link Ordered#HIGHEST_PRECEDENCE}) thì file quá lớn bị báo "lỗi hệ thống".
 * Lỗi vượt giới hạn xảy ra lúc Spring đọc multipart, trước khi vào controller, nên không có handler
 * nào khác bắt được.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class UploadExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Tệp upload vượt giới hạn: code=PAYLOAD_TOO_LARGE, traceId={}", traceId);
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "Tệp gửi lên vượt quá dung lượng cho phép.", traceId);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiErrorResponse> handleMalformedMultipart(Exception ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Request multipart không đọc được: code=INVALID_MEDIA, traceId={}", traceId);
        return error(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Dữ liệu tải lên không hợp lệ.", traceId);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        String traceId = TraceContext.currentTraceIdOrCreate();
        log.warn("Content-Type không được hỗ trợ: code=UNSUPPORTED_MEDIA_TYPE, traceId={}", traceId);
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Định dạng dữ liệu gửi lên không được hỗ trợ.", traceId);
    }

    private static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message,
                                                          String traceId) {
        return ResponseEntity.status(status)
                .header(TraceContext.HEADER_NAME, traceId)
                .body(new ApiErrorResponse(code, message, null, traceId));
    }
}
