package com.finora.loan.mapper;

import com.finora.loan.domain.LoanProduct;
import com.finora.loan.dto.response.LoanProductCatalogResponse;
import com.finora.loan.dto.response.LoanProductResponse;
import org.springframework.stereotype.Component;

@Component
public class LoanProductMapper {

    private static final String RATE_NOTICE =
            "Lãi suất cố định theo năm; lịch trả hiển thị là dự kiến cho tới ngày giải ngân thực tế.";

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
                "PERCENT_PER_YEAR",
                product.getRepaymentMethod(),
                RATE_NOTICE
        );
    }
}
