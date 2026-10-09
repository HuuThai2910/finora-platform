package com.finora.payment.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.payment.dto.request.CreateRepaymentRequest;
import com.finora.payment.dto.request.CreateEarlySettlementQuoteRequest;
import com.finora.payment.dto.request.CreateEarlySettlementRequest;
import com.finora.payment.dto.request.CreatePartialPrepaymentQuoteRequest;
import com.finora.payment.dto.request.CreatePartialPrepaymentRequest;
import com.finora.payment.dto.response.EarlySettlementQuoteResponse;
import com.finora.payment.dto.response.RepaymentResponse;
import com.finora.payment.dto.response.PartialPrepaymentQuoteResponse;
import com.finora.payment.service.repayment.PaymentRepaymentService;
import com.finora.payment.service.repayment.EarlySettlementQuoteService;
import com.finora.payment.service.repayment.PartialPrepaymentQuoteService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repayments")
@RequiredArgsConstructor
public class RepaymentController {
    private final PaymentRepaymentService service;
    private final EarlySettlementQuoteService quoteService;
    private final PartialPrepaymentQuoteService partialPrepaymentQuoteService;

    @PostMapping
    @RequirePin(PinScope.REPAYMENT)
    public RepaymentResponse create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRepaymentRequest request) {
        return service.create(request.loanApplicationId(), request.amount(), request.transactionDate(), idempotencyKey);
    }

    @PostMapping("/early-settlement-quotes")
    public EarlySettlementQuoteResponse createEarlySettlementQuote(
            @Valid @RequestBody CreateEarlySettlementQuoteRequest request) {
        return quoteService.create(request.loanApplicationId());
    }

    @GetMapping("/early-settlement-quotes/{quoteId}")
    public EarlySettlementQuoteResponse getEarlySettlementQuote(@PathVariable UUID quoteId) {
        return quoteService.get(quoteId);
    }

    @PostMapping("/early-settlement")
    @RequirePin(PinScope.REPAYMENT)
    public RepaymentResponse earlySettlement(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateEarlySettlementRequest request) {
        return service.createEarlySettlement(request.quoteId(), idempotencyKey);
    }

    @PostMapping("/partial-prepayment-quotes")
    public PartialPrepaymentQuoteResponse createPartialPrepaymentQuote(
            @Valid @RequestBody CreatePartialPrepaymentQuoteRequest request) {
        return partialPrepaymentQuoteService.create(request.loanApplicationId(), request.prepaidPrincipal());
    }

    @GetMapping("/partial-prepayment-quotes/{quoteId}")
    public PartialPrepaymentQuoteResponse getPartialPrepaymentQuote(@PathVariable UUID quoteId) {
        return partialPrepaymentQuoteService.get(quoteId);
    }

    @PostMapping("/partial-prepayment")
    @RequirePin(PinScope.REPAYMENT)
    public RepaymentResponse partialPrepayment(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePartialPrepaymentRequest request) {
        return service.createPartialPrepayment(request.quoteId(), idempotencyKey);
    }

    @GetMapping("/{repaymentId}")
    public RepaymentResponse get(@PathVariable UUID repaymentId) {
        return service.get(repaymentId);
    }
}
