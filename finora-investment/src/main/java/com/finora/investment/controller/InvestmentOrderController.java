package com.finora.investment.controller;

import com.finora.investment.dto.request.PlaceOrderRequest;
import com.finora.investment.dto.response.CommitmentResponse;
import com.finora.investment.dto.response.OrderResponse;
import com.finora.investment.dto.response.PortfolioResponse;
import com.finora.investment.service.FundingService;
import com.finora.investment.service.PortfolioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(
            @PathVariable Long listingId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request
    ) {
        return fundingService.placeOrder(listingId, idempotencyKey, request);
    }

    @PostMapping("/orders/{orderReference}/cancel")
    public OrderResponse cancelOrder(
            @PathVariable @NotBlank @Size(max = 50) String orderReference
    ) {
        return fundingService.cancelOrder(orderReference);
    }

    @GetMapping("/portfolio")
    public PortfolioResponse portfolio() {
        return portfolioService.portfolio();
    }

    @GetMapping("/commitments")
    public List<CommitmentResponse> commitments() {
        return portfolioService.myCommitments();
    }
}
