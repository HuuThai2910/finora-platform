package com.finora.investment.domain.orderbook.enums;

/**
 * Trạng thái một sổ lệnh trên danh sách Chợ chuyển nhượng quản trị. Nợ xấu đứng trên hết (khoản vay có phần vốn nợ xấu
 * dù còn lệnh hay không); còn lại là sổ có hay chưa có lệnh chờ khớp ở bất kỳ phía nào.
 */
public enum MarketBookState {
    HAS_ORDERS,
    NO_ORDERS,
    DEFAULTED;

    public static MarketBookState of(boolean defaulted, long restingOrders) {
        if (defaulted) {
            return DEFAULTED;
        }
        return restingOrders > 0 ? HAS_ORDERS : NO_ORDERS;
    }
}
