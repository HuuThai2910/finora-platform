package com.finora.investment.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.CommitmentResponse;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.dto.response.PortfolioResponse;
import com.finora.investment.service.FundingService;
import com.finora.investment.service.PortfolioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Đặt lệnh đầu tư và xem danh mục.
 *
 * <p>{@code Idempotency-Key} là bắt buộc cho thao tác tạo lệnh: đây là API có side effect
 * tài chính, gửi lại phải trả kết quả cũ chứ không được giữ tiền lần hai
 * (transaction-concurrency.md).</p>
 */
@RestController
@RequestMapping("/api/v1/investments")
@RequiredArgsConstructor
@Validated
public class InvestmentOrderController {

    private final FundingService fundingService;
    private final PortfolioService portfolioService;

    @PostMapping("/listings/{listingId}/orders")
    @RequirePin(PinScope.INVEST)
    public ResponseEntity<OrderResponse> placeOrder(
            @PathVariable Long listingId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(fundingService.placeOrder(listingId, idempotencyKey, request));
    }

    @PostMapping("/orders/{orderReference}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable @NotBlank @Size(max = 50) String orderReference
    ) {
        return ResponseEntity.ok(fundingService.cancelOrder(orderReference));
    }

    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioResponse> portfolio() {
        return ResponseEntity.ok(portfolioService.portfolio());
    }

    @GetMapping("/commitments")
    public ResponseEntity<List<CommitmentResponse>> commitments() {
        return ResponseEntity.ok(portfolioService.myCommitments());
    }
}
