package com.finora.payment.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.common.security.ratelimit.RateLimited;
import com.finora.payment.dto.request.CreateEarlySettlementQuoteRequest;
import com.finora.payment.dto.request.CreateEarlySettlementRequest;
import com.finora.payment.dto.request.CreatePartialPrepaymentQuoteRequest;
import com.finora.payment.dto.request.CreatePartialPrepaymentRequest;
import com.finora.payment.dto.request.CreateRepaymentRequest;
import com.finora.payment.dto.response.EarlySettlementQuoteResponse;
import com.finora.payment.dto.response.PartialPrepaymentQuoteResponse;
import com.finora.payment.dto.response.RepaymentResponse;
import com.finora.payment.dto.response.ScheduledDueResponse;
import com.finora.payment.service.repayment.EarlySettlementQuoteService;
import com.finora.payment.service.repayment.PartialPrepaymentQuoteService;
import com.finora.payment.service.repayment.PaymentRepaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repayments")
@RequiredArgsConstructor
public class RepaymentController {
    private final PaymentRepaymentService paymentRepaymentService;
    private final EarlySettlementQuoteService earlySettlementQuoteService;
    private final PartialPrepaymentQuoteService partialPrepaymentQuoteService;

    @PostMapping
    @RequirePin(PinScope.REPAYMENT)
    public ResponseEntity<RepaymentResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateRepaymentRequest request) {
        return ResponseEntity.ok(paymentRepaymentService.create(request.loanApplicationId(), request.amount(),
                request.transactionDate(), idempotencyKey));
    }

    /** Nghĩa vụ hiện tại theo Fineract để client hiển thị và gửi đúng số tiền trả kỳ; chỉ đọc, không trừ ví. */
    @GetMapping("/scheduled-due")
    public ResponseEntity<ScheduledDueResponse> scheduledDue(@RequestParam Long loanApplicationId) {
        return ResponseEntity.ok(paymentRepaymentService.scheduledDue(loanApplicationId));
    }

    // Báo giá đọc Fineract mỗi lần (D40): chung 30 báo giá/phút mỗi người dùng.
    @RateLimited(value = "repayment.quote", limit = 30, window = "PT1M")
    @PostMapping("/early-settlement-quotes")
    public ResponseEntity<EarlySettlementQuoteResponse> createEarlySettlementQuote(
            @Valid @RequestBody CreateEarlySettlementQuoteRequest request) {
        return ResponseEntity.ok(earlySettlementQuoteService.create(request.loanApplicationId()));
    }

    @GetMapping("/early-settlement-quotes/{quoteId}")
    public ResponseEntity<EarlySettlementQuoteResponse> getEarlySettlementQuote(@PathVariable UUID quoteId) {
        return ResponseEntity.ok(earlySettlementQuoteService.get(quoteId));
    }

    @PostMapping("/early-settlement")
    @RequirePin(PinScope.REPAYMENT)
    public ResponseEntity<RepaymentResponse> earlySettlement(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateEarlySettlementRequest request) {
        return ResponseEntity.ok(paymentRepaymentService.createEarlySettlement(request.quoteId(), idempotencyKey));
    }

    @RateLimited(value = "repayment.quote", limit = 30, window = "PT1M")
    @PostMapping("/partial-prepayment-quotes")
    public ResponseEntity<PartialPrepaymentQuoteResponse> createPartialPrepaymentQuote(
            @Valid @RequestBody CreatePartialPrepaymentQuoteRequest request) {
        return ResponseEntity.ok(partialPrepaymentQuoteService.create(request.loanApplicationId(),
                request.prepaidPrincipal()));
    }

    @GetMapping("/partial-prepayment-quotes/{quoteId}")
    public ResponseEntity<PartialPrepaymentQuoteResponse> getPartialPrepaymentQuote(@PathVariable UUID quoteId) {
        return ResponseEntity.ok(partialPrepaymentQuoteService.get(quoteId));
    }

    @PostMapping("/partial-prepayment")
    @RequirePin(PinScope.REPAYMENT)
    public ResponseEntity<RepaymentResponse> partialPrepayment(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePartialPrepaymentRequest request) {
        return ResponseEntity.ok(paymentRepaymentService.createPartialPrepayment(request.quoteId(), idempotencyKey));
    }

    @GetMapping("/{repaymentId}")
    public ResponseEntity<RepaymentResponse> get(@PathVariable UUID repaymentId) {
        return ResponseEntity.ok(paymentRepaymentService.get(repaymentId));
    }

    /**
     * Hủy khoản trả kỳ trước hạn đang chờ ghi nhận vào sổ nợ (D16); tiền hoàn về ví. Cần PIN vì hủy nhầm làm kỳ bị quá hạn.
     * Gọi lại với khoản đã hủy trả nguyên kết quả. {@code Idempotency-Key} chỉ dùng để gắn token PIN với đúng lần hủy
     * này (D38); thao tác vốn idempotent theo trạng thái khoản trả.
     */
    @PostMapping("/{repaymentId}/cancel")
    @RequirePin(PinScope.REPAYMENT)
    public ResponseEntity<RepaymentResponse> cancel(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID repaymentId) {
        return ResponseEntity.ok(paymentRepaymentService.cancelPending(repaymentId));
    }
}
