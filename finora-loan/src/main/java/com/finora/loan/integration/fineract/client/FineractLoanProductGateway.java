package com.finora.loan.integration.fineract.client;

import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import java.util.Optional;

/** Biên tích hợp Product giữa Loan Service và Apache Fineract. */
public interface FineractLoanProductGateway {

    /**
     * Tìm chính xác theo external ID để đối chiếu kết quả sau timeout và ngăn retry tạo Product logic thứ hai.
     */
    Optional<FineractProductCreationResult> findProductByExternalId(String externalId);

    /** Tạo Product core với external ID và idempotency key ổn định để retry không tạo resource thứ hai. */
    FineractProductCreationResult createProduct(FineractProductConfiguration configuration, String idempotencyKey);
}
