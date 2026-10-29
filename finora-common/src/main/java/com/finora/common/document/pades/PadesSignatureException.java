package com.finora.loan.service.contract.pades;

import lombok.Getter;

/** Lỗi kỹ thuật khi chuẩn bị, nhúng hoặc kiểm chữ ký PAdES; luôn fail closed, không lưu bản PDF lỗi. */
@Getter
public class PadesSignatureException extends RuntimeException {

    private final String code;

    public PadesSignatureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public PadesSignatureException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
