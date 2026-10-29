package com.finora.investment.service.orderbook;

import com.finora.investment.domain.orderbook.SettlementStatus;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Đọc chợ Notes cho màn quản trị: các lần khớp kèm hai bên mua bán và trạng thái thanh toán, cùng
 * dải số liệu tổng. Chỉ đọc — quản trị không đặt hay huỷ lệnh thay nhà đầu tư.
 */
public interface OrderBookAdminService {

    Page<AdminTradeResponse> trades(SettlementStatus status, Pageable pageable);

    OrderBookAdminSummaryResponse summary();
}
