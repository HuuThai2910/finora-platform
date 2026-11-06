package com.finora.payment.service.wallet;

import com.finora.payment.domain.wallet.enums.WalletOwnerType;

/**
 * Mở wallet idempotent theo owner/currency; chưa phải thao tác nạp tiền.
 */
public interface WalletAccountService {

    /**
     * Mở wallet nếu chưa có rồi đọc lại; gọi lặp với cùng loại chủ ví, mã chủ ví và loại tiền luôn trả cùng một wallet.
     */
    WalletView open(WalletOwnerType ownerType, String ownerId, String currency);
}
