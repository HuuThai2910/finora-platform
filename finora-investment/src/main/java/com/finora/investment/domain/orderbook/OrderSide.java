package com.finora.investment.domain.orderbook;

/**
 * Chiều của lệnh trên sổ: {@code BID} là lệnh mua Note, {@code ASK} là lệnh bán Note.
 */
public enum OrderSide {
    BID,
    ASK;

    public OrderSide opposite() {
        return this == BID ? ASK : BID;
    }
}
