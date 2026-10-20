package com.finora.investment.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.response.OrderIncidentResponse;
import com.finora.investment.repository.InvestmentOrderRepository;
import com.finora.investment.service.OrderTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Màn sự cố của admin cho lệnh góp vốn chưa khớp với ví (R3): lệnh chờ giữ tiền đã thử lại nhiều lần, khoản giữ chưa nhả
 * được, hoặc nhả bị dừng vì Payment trả lỗi chắc chắn. "Thử lại" chỉ hẹn lượt kế tiếp của worker ngay, không tự gọi Payment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderIncidentService {
    /**
     * Từ lần thử lỗi thứ 3 (khoảng 3–4 phút sau lần đầu) lệnh hiện cho vận hành; trước đó worker tự xử lý. Gauge
     * {@code finora.operations.incidents} (W3E) đếm theo cùng ngưỡng để số trên dashboard khớp màn sự cố.
     */
    public static final int ATTEMPT_THRESHOLD = 3;

    private final InvestmentOrderRepository orders;
    private final OrderTransactionService transactions;

    @Transactional(readOnly = true)
    public PageResponse<OrderIncidentResponse> list(int page, int size) {
        Page<OrderIncidentResponse> result = orders.findIncidents(ATTEMPT_THRESHOLD, PageRequest.of(page, size))
                .map(OrderIncidentResponse::from);
        return PageResponse.<OrderIncidentResponse>builder()
                .content(result.getContent()).page(result.getNumber()).size(result.getSize())
                .totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .last(result.isLast()).build();
    }

    public OrderIncidentResponse retry(String orderReference) {
        var order = transactions.retryIncident(orderReference);
        log.warn("Admin hẹn worker thử lại lệnh góp vốn: orderReference={}, status={}, releaseErrorCode={}",
                order.getOrderReference(), order.getStatus(), order.getReleaseErrorCode());
        return OrderIncidentResponse.from(order);
    }
}
