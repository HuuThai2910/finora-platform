package com.finora.loan.exception;

import com.finora.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

public final class LoanBusinessException extends BusinessException {

    public LoanBusinessException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static LoanBusinessException badRequest(String code, String message) {
        return new LoanBusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static LoanBusinessException forbidden(String code, String message) {
        return new LoanBusinessException(HttpStatus.FORBIDDEN, code, message);
    }

    public static LoanBusinessException conflict(String code, String message) {
        return new LoanBusinessException(HttpStatus.CONFLICT, code, message);
    }

    public static LoanBusinessException serviceUnavailable(String code, String message) {
        return new LoanBusinessException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
