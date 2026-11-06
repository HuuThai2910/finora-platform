package com.finora.payment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.common.security.ratelimit.RateLimited;
import com.finora.payment.dto.request.CreateWithdrawalRequest;
import com.finora.payment.dto.response.WithdrawalPolicyResponse;
import com.finora.payment.dto.response.WithdrawalResponse;
import com.finora.payment.integration.payout.PayoutSignature;
import com.finora.payment.service.withdrawal.WithdrawalProcessingService;
import com.finora.payment.service.withdrawal.WithdrawalService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Rút tiền từ ví về tài khoản ngân hàng (R4, W3B). Tạo lệnh cần PIN {@code WITHDRAW} gắn với đúng
 * {@code Idempotency-Key} (D38); số tài khoản chỉ đi trong body và không bao giờ được trả lại đầy đủ.
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
@Validated
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final WithdrawalProcessingService withdrawalProcessingService;

    @GetMapping("/withdrawal-policy")
    public ResponseEntity<WithdrawalPolicyResponse> policy() {
        return ResponseEntity.ok(withdrawalService.policy());
    }

    @PostMapping("/me/withdrawals")
    @RequirePin(PinScope.WITHDRAW)
    // Mỗi lệnh rút gọi provider chi tiền (D40): 10 lệnh/giờ mỗi người dùng.
    @RateLimited(value = "withdrawal.create", limit = 10, window = "PT1H")
    public ResponseEntity<WithdrawalResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWithdrawalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(withdrawalService.request(request, idempotencyKey));
    }

    @GetMapping("/me/withdrawals")
    public ResponseEntity<PageResponse<WithdrawalResponse>> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(withdrawalService.list(page, size));
    }

    @GetMapping("/me/withdrawals/{withdrawalId}")
    public ResponseEntity<WithdrawalResponse> get(@PathVariable UUID withdrawalId) {
        return ResponseEntity.ok(withdrawalService.get(withdrawalId));
    }

    /** Provider payout giả lập gọi lại (D43); không có JWT, tính xác thực nằm ở chữ ký HMAC trên nguyên body. */
    @PostMapping("/callbacks/mock-payout")
    public ResponseEntity<Map<String, String>> mockPayoutCallback(@RequestBody String body,
            @RequestHeader(value = PayoutSignature.HEADER, required = false) String signature) {
        withdrawalProcessingService.handleMockCallback(body, signature);
        return ResponseEntity.ok(Map.of("status", "OK"));
    }
}
