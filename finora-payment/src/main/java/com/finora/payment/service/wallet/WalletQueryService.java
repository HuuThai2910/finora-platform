package com.finora.payment.service.wallet;

import com.finora.payment.dto.response.WalletBalanceResponse;
import com.finora.payment.dto.response.WalletTransactionResponse;

import java.util.List;

/**
 * Xem số dư và sao kê ví VND của người dùng đang đăng nhập.
 */
public interface WalletQueryService {

    /** Số dư ví hiện tại; người dùng chưa có ví thì ví được mở với số dư 0. */
    WalletBalanceResponse currentBalance();

    /** Sao kê ví, tối đa 100 bút toán mới nhất; người dùng chưa có ví thì trả danh sách rỗng. */
    List<WalletTransactionResponse> currentStatement();
}
