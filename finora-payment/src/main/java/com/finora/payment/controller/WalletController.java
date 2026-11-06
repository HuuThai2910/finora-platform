package com.finora.payment.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.common.security.ratelimit.RateLimited;
import com.finora.payment.dto.request.CreateTopUpRequest;
import com.finora.payment.dto.request.ZaloPayCallbackRequest;
import com.finora.payment.dto.response.TopUpResponse;
import com.finora.payment.dto.response.WalletBalanceResponse;
import com.finora.payment.dto.response.WalletTransactionResponse;
import com.finora.payment.dto.response.ZaloPayCallbackResponse;
import com.finora.payment.service.topup.TopUpService;
import com.finora.payment.service.wallet.WalletQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletQueryService walletQueryService;
    private final TopUpService topUpService;

    @GetMapping("/health")
    public BaseResponse<String> healthCheck() {
        return BaseResponse.success("FINORA Payment Service is running! 💰");
    }

    @GetMapping("/me")
    public ResponseEntity<WalletBalanceResponse> me() {
        return ResponseEntity.ok(walletQueryService.currentBalance());
    }

    @GetMapping("/me/transactions")
    public ResponseEntity<List<WalletTransactionResponse>> transactions() {
        return ResponseEntity.ok(walletQueryService.currentStatement());
    }

    // Mỗi lệnh nạp tạo một đơn ở provider (ZaloPay) (D40): 10 lệnh/giờ mỗi người dùng.
    @RateLimited(value = "topup.create", limit = 10, window = "PT1H")
    @PostMapping("/me/top-ups")
    public ResponseEntity<TopUpResponse> createTopUp(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTopUpRequest request
    ) {
        return ResponseEntity.ok(topUpService.create(request.amount(), idempotencyKey));
    }

    @GetMapping("/me/top-ups/{topUpId}")
    public ResponseEntity<TopUpResponse> topUp(@PathVariable UUID topUpId) {
        return ResponseEntity.ok(topUpService.get(topUpId));
    }

    @PostMapping("/me/top-ups/{topUpId}/mock-complete")
    public ResponseEntity<TopUpResponse> completeMock(@PathVariable UUID topUpId) {
        return ResponseEntity.ok(topUpService.completeMock(topUpId));
    }

    @PostMapping("/callbacks/zalopay")
    public ResponseEntity<ZaloPayCallbackResponse> zaloPayCallback(@RequestBody ZaloPayCallbackRequest request) {
        try {
            if (!topUpService.processZaloPayCallback(request.data(), request.mac())) {
                return ResponseEntity.ok(new ZaloPayCallbackResponse(-1, "mac not equal"));
            }
            return ResponseEntity.ok(new ZaloPayCallbackResponse(1, "success"));
        } catch (RuntimeException exception) {
            return ResponseEntity.ok(new ZaloPayCallbackResponse(0, "retry"));
        }
    }
}
