package com.finora.common.exception;

/**
 * Exception khi không tìm thấy tài nguyên (404).
 * Ví dụ: "Không tìm thấy hồ sơ vay với ID 123."
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s không tìm thấy với %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
