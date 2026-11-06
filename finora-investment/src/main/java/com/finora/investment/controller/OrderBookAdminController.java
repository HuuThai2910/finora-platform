package com.finora.investment.controller;
import com.finora.common.dto.PageResponse;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import com.finora.investment.service.orderbook.OrderBookAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Giám sát chợ Notes cho quản trị: lần khớp kèm hai bên và trạng thái thanh toán, dải số liệu tổng.
 * {@code SecurityConfig} cho ADMIN và nhân viên có quyền Xem Chợ thứ cấp ({@code ops:secondary-market:view}).
 */
@RestController
@RequestMapping("/api/v1/investments/admin/order-books")
@RequiredArgsConstructor
@Validated
public class OrderBookAdminController {

    private final OrderBookAdminService orderBookAdminService;

    @GetMapping("/summary")
    public ResponseEntity<OrderBookAdminSummaryResponse> summary() {
        return ResponseEntity.ok(orderBookAdminService.summary());
    }

    /** Gửi thanh toán lại lần khớp "Cần đối soát"; 409 khi lần khớp không còn ở trạng thái lỗi. */
    @PostMapping("/trades/{tradeReference}/retry-settlement")
    public ResponseEntity<AdminTradeResponse> retrySettlement(@PathVariable @Size(max = 50) String tradeReference) {
        return ResponseEntity.ok(orderBookAdminService.requeueSettlement(tradeReference));
    }

    /** Note đã chuyển trong lần khớp: người đang giữ và lịch sử chuyển nhượng. */
    @GetMapping("/trades/{tradeReference}/notes")
    public ResponseEntity<java.util.List<AdminNoteOwnershipResponse>> tradeNotes(
            @PathVariable @Size(max = 50) String tradeReference) {
        return ResponseEntity.ok(orderBookAdminService.tradeNotes(tradeReference));
    }

    /** Lần khớp mới nhất trước; {@code settlement} lọc theo trạng thái thanh toán, bỏ trống là tất cả. */
    @GetMapping("/trades")
    public ResponseEntity<PageResponse<AdminTradeResponse>> trades(
            @RequestParam(required = false) SettlementStatus settlement,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Page<AdminTradeResponse> result = orderBookAdminService.trades(settlement, PageRequest.of(page, size));
        return ResponseEntity.ok(PageResponse.<AdminTradeResponse>builder()
                .content(result.getContent())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build());
    }
}
