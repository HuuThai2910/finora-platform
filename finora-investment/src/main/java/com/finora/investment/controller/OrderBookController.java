package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.dto.request.PlaceBookOrderRequest;
import com.finora.investment.dto.response.BookOrderResponse;
import com.finora.investment.dto.response.OrderBookPositionResponse;
import com.finora.investment.dto.response.OrderBookSnapshotResponse;
import com.finora.investment.dto.response.OrderBookSummaryResponse;
import com.finora.investment.service.orderbook.OrderBookCommandService;
import com.finora.investment.service.orderbook.OrderBookQueryService;
import com.finora.investment.service.orderbook.OrderBookStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Sổ lệnh Ask/Bid của chợ thứ cấp Notes (thay bảng tin {@code /investments/secondary}).
 *
 * <p>Mọi endpoint yêu cầu đăng nhập như bảng tin cũ. Ảnh chụp sổ không chứa mã nhà đầu tư — chỉ độ
 * sâu gộp theo mức giá — nhưng vẫn là dữ liệu giao dịch trên nền tảng, không phải niêm yết công
 * khai.</p>
 */
@RestController
@RequestMapping("/api/v1/investments/order-books")
@RequiredArgsConstructor
@Validated
public class OrderBookController {

    private final OrderBookCommandService commandService;
    private final OrderBookQueryService queryService;
    private final OrderBookStreamService streamService;

    /** Các khoản vay còn Note lưu hành, kèm giá mua/bán tốt nhất. */
    @GetMapping
    public PageResponse<OrderBookSummaryResponse> listBooks(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return toPageResponse(queryService.listBooks(PageRequest.of(page, size)));
    }

    @GetMapping("/{listingId}")
    public OrderBookSnapshotResponse snapshot(@PathVariable Long listingId) {
        return queryService.snapshot(listingId);
    }

    /**
     * Stream Server-Sent Events: sự kiện {@code snapshot} gửi ngay khi mở và mỗi lần sổ đổi; dòng
     * chú thích {@code keepalive} định kỳ giữ kết nối. Client giữ ảnh có {@code sequence} lớn nhất.
     */
    @GetMapping(path = "/{listingId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long listingId) {
        return streamService.subscribe(listingId);
    }

    /** Số Note còn đặt bán được, số đang nằm trong lệnh bán, và lệnh còn hiệu lực của tôi. */
    @GetMapping("/{listingId}/me")
    public OrderBookPositionResponse myPosition(@PathVariable Long listingId) {
        return queryService.myPosition(listingId, SecurityUtils.getCurrentUserId());
    }

    /**
     * Đặt lệnh giới hạn. {@code Idempotency-Key} bắt buộc: lệnh mua giữ tiền, gửi lại phải trả lệnh
     * cũ chứ không giữ tiền lần hai.
     */
    @PostMapping("/{listingId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public BookOrderResponse placeOrder(
            @PathVariable Long listingId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody PlaceBookOrderRequest request) {
        return commandService.placeOrder(listingId, idempotencyKey, request);
    }

    /** Lệnh của tôi trên mọi sổ, mới nhất trước; {@code active=true} chỉ lấy lệnh còn hiệu lực. */
    @GetMapping("/orders/mine")
    public PageResponse<BookOrderResponse> myOrders(
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return toPageResponse(queryService.myOrders(
                SecurityUtils.getCurrentUserId(), active, PageRequest.of(page, size)));
    }

    /** Huỷ phần chưa khớp của lệnh; phần đã khớp giữ nguyên. */
    @DeleteMapping("/orders/{orderReference}")
    public BookOrderResponse cancelOrder(@PathVariable @NotBlank @Size(max = 50) String orderReference) {
        return commandService.cancelOrder(orderReference);
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
