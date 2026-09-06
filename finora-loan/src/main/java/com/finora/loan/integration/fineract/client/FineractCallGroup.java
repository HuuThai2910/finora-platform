package com.finora.loan.integration.fineract.client;

/**
 * Tách circuit breaker theo nhóm chức năng để lỗi đồng bộ Product không chặn luồng
 * tính lịch trả nợ của borrower và ngược lại.
 */
public enum FineractCallGroup {
    PRODUCT("fineract-product"),
    SCHEDULE("fineract-schedule");

    private final String circuitBreakerName;

    FineractCallGroup(String circuitBreakerName) {
        this.circuitBreakerName = circuitBreakerName;
    }

    String circuitBreakerName() {
        return circuitBreakerName;
    }
}
