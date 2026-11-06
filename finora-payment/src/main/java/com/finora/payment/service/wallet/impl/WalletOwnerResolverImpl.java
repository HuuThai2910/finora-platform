package com.finora.payment.service.wallet.impl;

import com.finora.common.exception.BusinessException;
import com.finora.common.security.SecurityUtils;
import com.finora.payment.domain.wallet.enums.WalletOwnerType;
import com.finora.payment.service.wallet.WalletOwnerResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Xác định loại chủ ví (nhà đầu tư hoặc người vay) của người dùng đang đăng nhập theo vai trò trong JWT.
 * <p>
 * Vai trò nhà đầu tư được xét trước vai trò người vay.
 */
@Component
public class WalletOwnerResolverImpl implements WalletOwnerResolver {

    @Override
    public WalletOwnerType currentOwnerType() {
        if (SecurityUtils.hasRole("ROLE_INVESTOR")) {
            return WalletOwnerType.INVESTOR;
        }
        if (SecurityUtils.hasRole("ROLE_BORROWER")) {
            return WalletOwnerType.BORROWER;
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "WALLET_ROLE_REQUIRED",
                "Tài khoản không có vai trò được phép sử dụng ví");
    }
}
