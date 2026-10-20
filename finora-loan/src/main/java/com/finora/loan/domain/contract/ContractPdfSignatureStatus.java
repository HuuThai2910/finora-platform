package com.finora.loan.domain.contract;

/** PREPARED đang chờ provider ký; SIGNED đã nhúng vào chuỗi PDF; ABANDONED không bao giờ thuộc chuỗi chữ ký. */
public enum ContractPdfSignatureStatus {
    PREPARED,
    SIGNED,
    ABANDONED
}
