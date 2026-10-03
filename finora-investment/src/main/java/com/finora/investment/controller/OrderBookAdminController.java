package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.domain.orderbook.SettlementStatus;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import com.finora.investment.service.orderbook.OrderBookAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Giám sát chợ Notes cho quản trị: lần khớp kèm hai bên và trạng thái thanh toán, dải số liệu tổng.
 * Nằm dưới {@code /investments/admin/**} nên {@code SecurityConfig} chỉ cho vai trò ADMIN.
 */
@RestController
@RequestMapping("/api/v1/investments/admin/order-books")
@RequiredArgsConstructor
@Validated
public class OrderBookAdminController {

    private final OrderBookAdminService adminService;

    @GetMapping("/summary")
    public OrderBookAdminSummaryResponse summary() {
        return adminService.summary();
    }

    /** Lần khớp mới nhất trước; {@code settlement} lọc theo trạng thái thanh toán, bỏ trống là tất cả. */
    @GetMapping("/trades")
    public PageResponse<AdminTradeResponse> trades(
            @RequestParam(required = false) SettlementStatus settlement,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<AdminTradeResponse> result = adminService.trades(settlement, PageRequest.of(page, size));
        return PageResponse.<AdminTradeResponse>builder()
                .content(result.getContent())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }
}
