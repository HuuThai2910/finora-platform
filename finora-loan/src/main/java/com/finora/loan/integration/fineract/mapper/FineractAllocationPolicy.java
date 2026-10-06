package com.finora.loan.integration.fineract.mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Một nơi duy nhất ánh xạ version cấu hình FINORA sang chiến lược immutable của Fineract Product. */
final class FineractAllocationPolicy {
    static final String STANDARD = "mifos-standard-strategy";
    static final String ADVANCED = "advanced-payment-allocation-strategy";

    private static final List<String> FINORA_V2_ORDER = List.of(
            "PAST_DUE_PRINCIPAL", "PAST_DUE_INTEREST", "PAST_DUE_FEE", "PAST_DUE_PENALTY",
            "DUE_PRINCIPAL", "DUE_INTEREST", "DUE_FEE", "DUE_PENALTY",
            "IN_ADVANCE_PRINCIPAL", "IN_ADVANCE_INTEREST", "IN_ADVANCE_FEE", "IN_ADVANCE_PENALTY");

    private FineractAllocationPolicy() {}

    static String strategy(String configVersion) {
        String value = normalize(configVersion);
        if (value.endsWith("V1")) return STANDARD;
        if (value.endsWith("V2")) return ADVANCED;
        throw new IllegalArgumentException("FINERACT_PRODUCT_CONFIG_VERSION không được hỗ trợ: " + configVersion);
    }

    static boolean advanced(String configVersion) {
        return ADVANCED.equals(strategy(configVersion));
    }

    static List<Map<String, Object>> paymentAllocation() {
        List<Map<String, Object>> order = new ArrayList<>();
        for (int index = 0; index < FINORA_V2_ORDER.size(); index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("paymentAllocationRule", FINORA_V2_ORDER.get(index));
            item.put("order", index + 1);
            order.add(item);
        }
        Map<String, Object> defaultRule = new LinkedHashMap<>();
        defaultRule.put("transactionType", "DEFAULT");
        defaultRule.put("paymentAllocationOrder", order);
        defaultRule.put("futureInstallmentAllocationRule", "REAMORTIZATION");
        return List.of(defaultRule);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("configVersion không được trống");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
