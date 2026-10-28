package com.finora.loan.integration.fineract.mapper;

import com.finora.loan.domain.product.RepaymentMethod;
import org.springframework.stereotype.Component;

/**
 * Một nguồn ánh xạ duy nhất giữa chính sách trả nợ FINORA và mã Fineract 1.15.
 * Lãi luôn tính trên dư nợ còn lại; cách phân bổ gốc là trả góp đều hoặc gốc trả đều theo đúng Product snapshot.
 * Kiểu tính lãi theo ngày/kỳ đi theo version cấu hình core (V4 theo ngày, V1–V3 theo kỳ).
 */
@Component
public class FineractRepaymentPolicy {

    public int amortizationType(RepaymentMethod repaymentMethod) {
        return switch (repaymentMethod) {
            case ANNUITY -> 1;
            case EQUAL_PRINCIPAL -> 0;
        };
    }

    public int interestType() {
        return 0;
    }

    public int interestCalculationPeriodType(String configVersion) {
        return FineractAllocationPolicy.interestCalculationPeriodType(configVersion);
    }
}
