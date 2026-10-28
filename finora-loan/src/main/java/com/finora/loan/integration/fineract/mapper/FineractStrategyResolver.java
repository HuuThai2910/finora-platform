package com.finora.loan.integration.fineract.mapper;

import com.finora.loan.domain.product.RepaymentMethod;

/** Cho gateway ngoài package mapper dùng đúng chiến lược Product theo version cấu hình core. */
public final class FineractStrategyResolver {
    private FineractStrategyResolver() {}

    public static String strategy(String configVersion, RepaymentMethod repaymentMethod) {
        return FineractAllocationPolicy.strategy(configVersion, repaymentMethod);
    }

    /** Khoản vay booking phải khai cùng kiểu tính lãi với Product (theo ngày ở V4, theo kỳ ở V1–V3). */
    public static int interestCalculationPeriodType(String configVersion) {
        return FineractAllocationPolicy.interestCalculationPeriodType(configVersion);
    }
}
