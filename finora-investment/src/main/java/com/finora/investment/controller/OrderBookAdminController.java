package com.finora.investment.controller;
import com.finora.common.dto.PageResponse;
import com.finora.investment.domain.orderbook.enums.MarketBookState;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.orderbook.enums.TradeProgress;
import com.finora.investment.dto.request.AdminOrderBookFilter;
import com.finora.investment.dto.request.AdminTradeFilter;
import com.finora.investment.dto.response.AdminListingNoteResponse;
import com.finora.investment.dto.response.AdminNoteOwnershipResponse;
import com.finora.investment.dto.response.AdminOrderBookDetailResponse;
import com.finora.investment.dto.response.AdminOrderBookResponse;
import com.finora.investment.dto.response.AdminTradeResponse;
import com.finora.investment.dto.response.OrderBookAdminSummaryResponse;
import com.finora.investment.service.orderbook.AdminOrderBookService;
import com.finora.investment.service.orderbook.OrderBookAdminService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Chợ chuyển nhượng cho quản trị: danh sách và chi tiết sổ lệnh, phần vốn của khoản vay, lần khớp kèm hai bên và
 * trạng thái thanh toán/giấy xác nhận, dải số liệu tổng. {@code SecurityConfig} cho ADMIN và nhân viên có quyền Xem Chợ
 * thứ cấp ({@code ops:secondary-market:view}); gửi thanh toán lại cần quyền Thao tác.
 */
@RestController
@RequestMapping("/api/v1/investments/admin/order-books")
@RequiredArgsConstructor
@Validated
public class OrderBookAdminController {

    private final OrderBookAdminService orderBookAdminService;
    private final AdminOrderBookService adminOrderBookService;

    /**
     * Các khoản vay còn phần vốn lưu hành. {@code q} khớp một phần mã hồ sơ/hợp đồng hoặc họ tên người vay;
     * {@code grades=A,B}; {@code sort} {@code RECENT} (mặc định, khớp gần nhất trước), {@code DEPTH}, {@code RATE}.
     */
    @GetMapping
    public ResponseEntity<PageResponse<AdminOrderBookResponse>> books(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) MarketBookState state,
            @RequestParam(required = false) @Size(max = 10) List<@Size(max = 5) String> grades,
            @RequestParam(required = false) BigDecimal rateMin,
            @RequestParam(required = false) BigDecimal rateMax,
            @RequestParam(required = false) AdminOrderBookFilter.Sort sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(toPageResponse(adminOrderBookService.books(
                new AdminOrderBookFilter(q, state, grades, rateMin, rateMax, sort), PageRequest.of(page, size))));
    }

    @GetMapping("/summary")
    public ResponseEntity<OrderBookAdminSummaryResponse> summary() {
        return ResponseEntity.ok(orderBookAdminService.summary());
    }

    /** Chi tiết một sổ: khoản vay, trả nợ, số liệu chuyển nhượng; độ sâu sổ đọc ở API sổ lệnh công khai. */
    @GetMapping("/{listingId}")
    public ResponseEntity<AdminOrderBookDetailResponse> book(@PathVariable Long listingId) {
        return ResponseEntity.ok(adminOrderBookService.book(listingId));
    }

    /** Phần vốn của khoản vay theo thứ tự phát hành; {@code q} khớp mã phần vốn hoặc họ tên người đang nắm giữ. */
    @GetMapping("/{listingId}/notes")
    public ResponseEntity<PageResponse<AdminListingNoteResponse>> notes(
            @PathVariable Long listingId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(toPageResponse(adminOrderBookService.notes(listingId, q, PageRequest.of(page, size))));
    }

    /** Gửi thanh toán lại lần khớp "Cần đối soát"; 409 khi lần khớp không còn ở trạng thái lỗi. */
    @PostMapping("/trades/{tradeReference}/retry-settlement")
    public ResponseEntity<AdminTradeResponse> retrySettlement(@PathVariable @Size(max = 50) String tradeReference) {
        return ResponseEntity.ok(orderBookAdminService.requeueSettlement(tradeReference));
    }

    /** Một lần khớp kèm khoản vay, họ tên hai bên và giấy xác nhận. */
    @GetMapping("/trades/{tradeReference}")
    public ResponseEntity<AdminTradeResponse> trade(@PathVariable @Size(max = 50) String tradeReference) {
        return ResponseEntity.ok(orderBookAdminService.trade(tradeReference));
    }

    /** Note đã chuyển trong lần khớp: người đang giữ, giá đang rao bán và lịch sử chuyển nhượng. */
    @GetMapping("/trades/{tradeReference}/notes")
    public ResponseEntity<List<AdminNoteOwnershipResponse>> tradeNotes(
            @PathVariable @Size(max = 50) String tradeReference) {
        return ResponseEntity.ok(orderBookAdminService.tradeNotes(tradeReference));
    }

    /**
     * Lần khớp mới nhất trước. {@code listingId} chỉ lấy một sổ; {@code progress} chặng gộp ({@code SETTLING},
     * {@code DOC_PENDING}, {@code DONE}); {@code party} một phần họ tên người bán/người mua; ngày khớp theo giờ Việt Nam,
     * gồm hai đầu; số tiền tính bằng đồng. {@code settlement} và {@code q} giữ như trước.
     */
    @GetMapping("/trades")
    public ResponseEntity<PageResponse<AdminTradeResponse>> trades(
            @RequestParam(required = false) Long listingId,
            @RequestParam(required = false) SettlementStatus settlement,
            @RequestParam(required = false) TradeProgress progress,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 100) String party,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate executedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate executedTo,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        AdminTradeFilter filter = new AdminTradeFilter(listingId, settlement, progress, q, party, executedFrom,
                executedTo, amountMin, amountMax);
        return ResponseEntity.ok(
                toPageResponse(orderBookAdminService.searchTrades(filter, PageRequest.of(page, size))));
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
}
