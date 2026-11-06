package com.finora.loan.mapper.product;

import com.finora.loan.config.LoanPricingDisclosureProperties;
import com.finora.loan.domain.product.entity.LoanProduct;
import com.finora.loan.dto.product.response.LoanProductCatalogResponse;
import com.finora.loan.dto.product.response.LoanProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanProductMapper {

    private final LoanPricingDisclosureProperties loanPricingDisclosureProperties;

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
                product.getMinAnnualInterestRate(),
                product.getAnnualInterestRate(),
                product.getMaxAnnualInterestRate(),
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
                product.getMinAnnualInterestRate(),
                product.getAnnualInterestRate(),
                product.getMaxAnnualInterestRate(),
                loanPricingDisclosureProperties.interestRateUnit(),
                product.getRepaymentMethod(),
                loanPricingDisclosureProperties.rateNotice()
        );
    }
}
