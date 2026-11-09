package com.finora.loan.service.contract;

import java.time.Instant;

/**
 * Sinh mã hợp đồng vay: mã tra cứu {@code LC-} + 20 ký tự hex viết hoa (khóa API/event) và mã hiển thị
 * {@code HĐ-yyyyMMdd-XXXX} cho người dùng.
 */
public interface ContractNumberGenerator {

    /** Mã hợp đồng mới. */
    String next();

    /**
     * Mã hiển thị chưa có hợp đồng nào dùng, theo ngày Việt Nam của {@code createdAt}. Gọi trong transaction lập hợp
     * đồng; ràng buộc {@code uq_loan_contracts_contract_code} vẫn chặn trường hợp hai luồng cùng lúc chọn trùng mã.
     */
    String nextCode(Instant createdAt);
}
