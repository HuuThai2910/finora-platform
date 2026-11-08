package com.finora.loan.domain.contract.enums;

/**
 * PREPARED đang chờ provider ký; SIGNED đã nhúng vào chuỗi PDF; ABANDONED không bao giờ thuộc chuỗi chữ ký;
 * SUPERSEDED đã ký trên văn bản trước khi phát hành lại (giữ làm bằng chứng, không còn thuộc chuỗi hiện hành).
 */
public enum ContractPdfSignatureStatus {
    PREPARED,
    SIGNED,
    ABANDONED,
    SUPERSEDED
}
