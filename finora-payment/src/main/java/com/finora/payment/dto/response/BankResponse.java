package com.finora.payment.dto.response;

import com.finora.payment.service.bank.BankInfo;

/** Một ngân hàng trong danh bạ NAPAS mà app cho chọn khi liên kết. */
public record BankResponse(String code, String bin, String shortName, String name, String logoUrl) {

    public static BankResponse from(BankInfo bank) {
        return new BankResponse(bank.code(), bank.bin(), bank.shortName(), bank.name(), bank.logo());
    }
}
