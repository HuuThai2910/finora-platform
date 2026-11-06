package com.finora.payment.service.wallet;

import com.finora.payment.domain.wallet.enums.WalletOwnerType;

/**
 * Xác định loại chủ ví (nhà đầu tư hoặc người vay) của người dùng đang đăng nhập theo vai trò trong JWT.
 */
public interface WalletOwnerResolver {

    /** Loại chủ ví của người dùng hiện tại; không có vai trò được dùng ví thì {@code 403 WALLET_ROLE_REQUIRED}. */
    WalletOwnerType currentOwnerType();
}
