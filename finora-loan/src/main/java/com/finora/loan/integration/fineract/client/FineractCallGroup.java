package com.finora.loan.integration.fineract.client;

/**
 * Tách circuit breaker theo nhóm chức năng để lỗi đồng bộ Product không chặn luồng
 * tính lịch trả nợ của borrower và ngược lại.
 */
public enum FineractCallGroup {
    PRODUCT("fineract-product"),
    SCHEDULE("fineract-schedule"),
    BOOKING("fineract-booking"),
    SERVICING("fineract-servicing"),
    RESTRUCTURING("fineract-restructuring");

    private final String circuitBreakerName;

    FineractCallGroup(String circuitBreakerName) {
        this.circuitBreakerName = circuitBreakerName;
    }

    String circuitBreakerName() {
        return circuitBreakerName;
    }
}
