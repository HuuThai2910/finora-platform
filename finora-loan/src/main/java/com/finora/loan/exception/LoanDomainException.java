package com.finora.loan.exception;

import lombok.Getter;

/**
 * Lỗi invariant thuần của Loan domain. Exception không biết HTTP; tầng web chịu trách nhiệm
 * chuyển {@link Kind} thành status phù hợp.
 */
@Getter
public final class LoanDomainException extends RuntimeException {

    public enum Kind {
        INVALID_INPUT,
        CONFLICT,
        FORBIDDEN
    }

    private final Kind kind;
    private final String code;

    private LoanDomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static LoanDomainException invalidInput(String code, String message) {
        return new LoanDomainException(Kind.INVALID_INPUT, code, message);
    }

    public static LoanDomainException conflict(String code, String message) {
        return new LoanDomainException(Kind.CONFLICT, code, message);
    }

    public static LoanDomainException forbidden(String code, String message) {
        return new LoanDomainException(Kind.FORBIDDEN, code, message);
    }
}
