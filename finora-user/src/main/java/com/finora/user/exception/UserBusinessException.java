package com.finora.user.exception;

/**
 * Exception nghiệp vụ cho user service.
 */
public class UserBusinessException extends RuntimeException {

    public UserBusinessException(String message) {
        super(message);
    }

    public UserBusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
