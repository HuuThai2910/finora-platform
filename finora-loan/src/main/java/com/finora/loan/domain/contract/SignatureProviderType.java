package com.finora.loan.domain.contract;

/** Provider tạo bằng chứng ký; MOCK chỉ dùng cho phát triển và không phải chữ ký số. */
public enum SignatureProviderType {
    MOCK,
    VNPT_SMART_CA
}
