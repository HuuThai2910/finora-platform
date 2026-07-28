package com.finora.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Exception cho các lỗi nghiệp vụ (Business Logic Error).
 * Ví dụ: "Hồ sơ vay đã bị từ chối, không thể duyệt lại."
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(String message) {
        super(message);
        this.status = HttpStatus.BAD_REQUEST;
    }

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
