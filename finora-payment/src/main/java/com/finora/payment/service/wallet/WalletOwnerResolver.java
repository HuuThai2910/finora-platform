package com.finora.payment.service.wallet;

import com.finora.common.security.SecurityUtils;
import com.finora.common.exception.BusinessException;
import com.finora.payment.domain.wallet.WalletOwnerType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WalletOwnerResolver {

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
