package com.finora.payment.service.withdrawal;

import java.util.Arrays;
import java.util.Optional;

/**
 * Ngân hàng nhận tiền được chọn khi rút (danh sách demo, R4). Mã theo tên viết tắt phổ biến; nối provider thật thì đổi
 * sang mã BIN/NAPAS mà provider yêu cầu.
 */
public enum WithdrawalBank {
    VCB("Vietcombank"),
    BIDV("BIDV"),
    CTG("VietinBank"),
    AGR("Agribank"),
    TCB("Techcombank"),
    MB("MB Bank"),
    ACB("ACB"),
    VPB("VPBank"),
    TPB("TPBank"),
    STB("Sacombank");

    private final String displayName;

    WithdrawalBank(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<WithdrawalBank> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase();
        return Arrays.stream(values()).filter(bank -> bank.name().equals(normalized)).findFirst();
    }
}
