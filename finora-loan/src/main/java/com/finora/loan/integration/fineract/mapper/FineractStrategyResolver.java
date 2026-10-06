package com.finora.loan.integration.fineract.mapper;

/** Public boundary dùng bởi booking adapter; allocation payload vẫn được đóng gói trong mapper package. */
public final class FineractStrategyResolver {
    private FineractStrategyResolver() {}

    public static String strategy(String configVersion) {
        return FineractAllocationPolicy.strategy(configVersion);
    }
}
