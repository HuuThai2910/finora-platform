package com.finora.investment.exception;

import lombok.Getter;

/**
 * Lỗi invariant thuần của Investment domain. Exception không biết HTTP;
 * tầng web chịu trách nhiệm chuyển {@link Kind} thành status phù hợp.
 */
@Getter
public final class InvestmentDomainException extends RuntimeException {

    public enum Kind {
        INVALID_INPUT,
        CONFLICT,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND
    }

    private final Kind kind;
    private final String code;

    private InvestmentDomainException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public static InvestmentDomainException invalidInput(String code, String message) {
        return new InvestmentDomainException(Kind.INVALID_INPUT, code, message);
    }

    public static InvestmentDomainException conflict(String code, String message) {
        return new InvestmentDomainException(Kind.CONFLICT, code, message);
    }

    public static InvestmentDomainException unauthorized(String code, String message) {
        return new InvestmentDomainException(Kind.UNAUTHORIZED, code, message);
    }

    public static InvestmentDomainException forbidden(String code, String message) {
        return new InvestmentDomainException(Kind.FORBIDDEN, code, message);
    }

    public static InvestmentDomainException notFound(String code, String message) {
        return new InvestmentDomainException(Kind.NOT_FOUND, code, message);
    }
}
