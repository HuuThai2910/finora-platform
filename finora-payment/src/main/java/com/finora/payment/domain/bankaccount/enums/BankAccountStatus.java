package com.finora.payment.domain.bankaccount.enums;

/** Trạng thái tài khoản ngân hàng đã liên kết. */
public enum BankAccountStatus {
    /** Đang dùng được để rút tiền. */
    ACTIVE,
    /** Người dùng đã huỷ liên kết. */
    REMOVED
}
