package com.finora.loan.integration.fineract;

import java.util.Optional;

/** Biên tích hợp Product giữa Loan Service và Apache Fineract. */
public interface FineractLoanProductGateway {

    /**
     * Tìm chính xác theo external ID để đối chiếu kết quả sau timeout và ngăn retry tạo Product logic thứ hai.
     */
    Optional<FineractProductCreationResult> findProductByExternalId(String externalId);

    FineractProductCreationResult createProduct(FineractProductConfiguration configuration, String idempotencyKey);
}
