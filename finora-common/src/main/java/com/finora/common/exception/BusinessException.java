package com.finora.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Exception cho các lỗi nghiệp vụ (Business Logic Error).
 * Ví dụ: "Hồ sơ vay đã bị từ chối, không thể duyệt lại."
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final String DEFAULT_CODE = "BUSINESS_RULE_VIOLATION";

    private final HttpStatus status;
    private final String code;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, DEFAULT_CODE, message);
    }

    public BusinessException(HttpStatus status, String message) {
        this(status, DEFAULT_CODE, message);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
