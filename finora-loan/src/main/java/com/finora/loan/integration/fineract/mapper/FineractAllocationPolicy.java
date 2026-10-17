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
        if (value.endsWith("V2") || value.endsWith("V3") || value.endsWith("V4")) return ADVANCED;
        throw new IllegalArgumentException("FINERACT_PRODUCT_CONFIG_VERSION không được hỗ trợ: " + configVersion);
    }

    static boolean advanced(String configVersion) {
        return ADVANCED.equals(strategy(configVersion));
    }

    static List<Map<String, Object>> paymentAllocation(String configVersion) {
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
        defaultRule.put("futureInstallmentAllocationRule", futureInstallmentAllocationRule(configVersion));
        return List.of(defaultRule);
    }

    /**
     * V2 giữ nguyên lịch sử re-amortization. V3/V4 phân bổ vào các kỳ gần nhất trước để khoản trả thêm làm ngắn thời
     * gian còn lại, thay vì giảm đều số tiền của mọi kỳ tương lai.
     */
    static String futureInstallmentAllocationRule(String configVersion) {
        String value = normalize(configVersion);
        if (value.endsWith("V2")) return "REAMORTIZATION";
        if (value.endsWith("V3") || value.endsWith("V4")) return "NEXT_INSTALLMENT";
        throw new IllegalArgumentException("Không có future allocation rule cho: " + configVersion);
    }

    /**
     * V4 (quyết định D15, sửa B28/B29) bật tính lại lãi: lãi chạy theo dư nợ thực tế và số ngày thực tế, nên gốc trả
     * thêm không còn chịu lãi các kỳ sau và tất toán chỉ thu lãi đến ngày tất toán. V1–V3 tính sẵn lãi cả lịch nên trả
     * trước/tất toán kéo theo lãi tương lai.
     */
    static boolean interestRecalculation(String configVersion) {
        return normalize(configVersion).endsWith("V4");
    }

    /**
     * Fineract 1.15 chỉ cho bật tính lại lãi khi lãi tính theo ngày ({@code 0}); V1–V3 tính theo kỳ trả nợ ({@code 1}).
     * Product, lịch tính thử và khoản vay booking phải dùng cùng giá trị, nếu không Fineract từ chối khoản vay.
     */
    static int interestCalculationPeriodType(String configVersion) {
        return interestRecalculation(configVersion) ? 0 : 1;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("configVersion không được trống");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
