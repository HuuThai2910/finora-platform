package com.finora.loan.integration.fineract.mapper;

import com.finora.loan.domain.product.RepaymentMethod;
import org.springframework.stereotype.Component;

/**
 * Một nguồn ánh xạ duy nhất giữa chính sách trả nợ FINORA và mã Fineract 1.15.
 * MVP luôn dùng dư nợ giảm dần và tính lãi theo cùng chu kỳ trả nợ.
 */
@Component
public class FineractRepaymentPolicy {

    public int amortizationType(RepaymentMethod repaymentMethod) {
        return repaymentMethod == RepaymentMethod.ANNUITY ? 1 : 0;
    }

    public int interestType() {
        return 0;
    }

    public int interestCalculationPeriodType() {
        return 1;
    }
}
