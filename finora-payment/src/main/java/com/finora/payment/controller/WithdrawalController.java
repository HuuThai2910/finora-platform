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
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    private final WithdrawalProcessingService processingService;

    @GetMapping("/withdrawal-policy")
    public WithdrawalPolicyResponse policy() {
        return withdrawalService.policy();
    }

    @PostMapping("/me/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePin(PinScope.WITHDRAW)
    // Mỗi lệnh rút gọi provider chi tiền (D40): 10 lệnh/giờ mỗi người dùng.
    @RateLimited(value = "withdrawal.create", limit = 10, window = "PT1H")
    public WithdrawalResponse create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateWithdrawalRequest request) {
        return withdrawalService.request(request, idempotencyKey);
    }

    @GetMapping("/me/withdrawals")
    public PageResponse<WithdrawalResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return withdrawalService.list(page, size);
    }

    @GetMapping("/me/withdrawals/{withdrawalId}")
    public WithdrawalResponse get(@PathVariable UUID withdrawalId) {
        return withdrawalService.get(withdrawalId);
    }

    /** Provider payout giả lập gọi lại (D43); không có JWT, tính xác thực nằm ở chữ ký HMAC trên nguyên body. */
    @PostMapping("/callbacks/mock-payout")
    public Map<String, String> mockPayoutCallback(@RequestBody String body,
            @RequestHeader(value = PayoutSignature.HEADER, required = false) String signature) {
        processingService.handleMockCallback(body, signature);
        return Map.of("status", "OK");
    }
}
