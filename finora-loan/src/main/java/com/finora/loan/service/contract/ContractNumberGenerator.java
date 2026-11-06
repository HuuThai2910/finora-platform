package com.finora.loan.service.contract;

/**
 * Sinh mã hợp đồng vay dạng {@code LC-} và 20 ký tự hex viết hoa.
 */
public interface ContractNumberGenerator {

    /** Mã hợp đồng mới. */
    String next();
}
