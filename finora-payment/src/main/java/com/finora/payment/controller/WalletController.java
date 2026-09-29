package com.finora.payment.controller;

import com.finora.common.dto.BaseResponse;
import com.finora.payment.dto.request.CreateTopUpRequest;
import com.finora.payment.dto.request.ZaloPayCallbackRequest;
import com.finora.payment.dto.response.TopUpResponse;
import com.finora.payment.dto.response.WalletBalanceResponse;
import com.finora.payment.dto.response.WalletTransactionResponse;
import com.finora.payment.dto.response.ZaloPayCallbackResponse;
import com.finora.payment.service.topup.TopUpService;
import com.finora.payment.service.wallet.WalletQueryService;
import jakarta.validation.Valid;
import java.util.List;
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
    public WalletBalanceResponse me() {
        return walletQueryService.currentBalance();
    }

    @GetMapping("/me/transactions")
    public List<WalletTransactionResponse> transactions() {
        return walletQueryService.currentStatement();
    }

    @PostMapping("/me/top-ups")
    public TopUpResponse createTopUp(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTopUpRequest request
    ) {
        return topUpService.create(request.amount(), idempotencyKey);
    }

    @GetMapping("/me/top-ups/{topUpId}")
    public TopUpResponse topUp(@PathVariable UUID topUpId) {
        return topUpService.get(topUpId);
    }

    @PostMapping("/me/top-ups/{topUpId}/mock-complete")
    public TopUpResponse completeMock(@PathVariable UUID topUpId) {
        return topUpService.completeMock(topUpId);
    }

    @PostMapping("/callbacks/zalopay")
    public ZaloPayCallbackResponse zaloPayCallback(@RequestBody ZaloPayCallbackRequest request) {
        try {
            if (!topUpService.processZaloPayCallback(request.data(), request.mac())) {
                return new ZaloPayCallbackResponse(-1, "mac not equal");
            }
            return new ZaloPayCallbackResponse(1, "success");
        } catch (RuntimeException exception) {
            return new ZaloPayCallbackResponse(0, "retry");
        }
    }
}
