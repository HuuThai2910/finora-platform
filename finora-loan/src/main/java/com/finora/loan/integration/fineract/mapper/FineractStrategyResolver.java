package com.finora.loan.integration.fineract.mapper;

/** Cho gateway ngoài package mapper dùng đúng chiến lược Product theo version cấu hình core. */
public final class FineractStrategyResolver {
    private FineractStrategyResolver() {}

    public static String strategy(String configVersion) {
        return FineractAllocationPolicy.strategy(configVersion);
    }

    /** Khoản vay booking phải khai cùng kiểu tính lãi với Product (theo ngày ở V4, theo kỳ ở V1–V3). */
    public static int interestCalculationPeriodType(String configVersion) {
        return FineractAllocationPolicy.interestCalculationPeriodType(configVersion);
    }
}
