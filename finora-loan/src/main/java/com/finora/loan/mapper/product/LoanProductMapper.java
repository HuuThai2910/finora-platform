package com.finora.loan.mapper.product;

import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.product.LoanProduct;
import com.finora.loan.dto.product.response.LoanProductCatalogResponse;
import com.finora.loan.dto.product.response.LoanProductResponse;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class LoanProductMapper {

    private final LoanPricingDisclosureProperties disclosureProperties;

    public LoanProductResponse toResponse(LoanProduct product) {
        return new LoanProductResponse(
                product.getId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getMinAmount(),
                product.getMaxAmount(),
                product.getMinTermMonths(),
                product.getMaxTermMonths(),
                product.getAnnualInterestRate(),
                product.getRepaymentMethod(),
                product.getStatus(),
                product.getCoreSyncStatus(),
                product.getCurrentCoreMappingId(),
                product.getConfigurationVersion(),
                product.getVersion(),
                product.getCreatedBy(),
                product.getUpdatedBy(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    public LoanProductCatalogResponse toCatalogResponse(LoanProduct product) {
        return new LoanProductCatalogResponse(
                product.getId(),
                product.getCode(),
                product.getName(),
                product.getDescription(),
                product.getMinAmount(),
                product.getMaxAmount(),
                product.getMinTermMonths(),
                product.getMaxTermMonths(),
                product.getAnnualInterestRate(),
                disclosureProperties.interestRateUnit(),
                product.getRepaymentMethod(),
                disclosureProperties.rateNotice()
        );
    }
}
