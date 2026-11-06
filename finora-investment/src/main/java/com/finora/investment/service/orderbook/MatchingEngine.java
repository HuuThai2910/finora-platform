package com.finora.investment.service.orderbook;

import com.finora.investment.domain.orderbook.entity.BookOrder;
import com.finora.investment.domain.orderbook.entity.BookTrade;
import com.finora.investment.domain.orderbook.entity.OrderBook;

import java.time.Instant;
import java.util.List;

/**
 * Khớp một lệnh vừa vào sổ với các lệnh đối ứng đang nằm chờ, rồi thực hiện từng lần khớp.
 */
public interface MatchingEngine {

    /**
     * Khớp {@code incoming} tới khi hết số lượng, hết lệnh đối ứng khớp được giá, hoặc chạm lệnh
     * của chính người đặt.
     *
     * @return các lần khớp đã thực hiện, theo thứ tự
     */
    List<BookTrade> match(OrderBook book, BookOrder incoming, Instant now);
}
