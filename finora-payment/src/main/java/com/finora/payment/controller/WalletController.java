package com.finora.payment.controller;

import com.finora.common.dto.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    @GetMapping("/health")
    public BaseResponse<String> healthCheck() {
        return BaseResponse.success("FINORA Payment Service is running! 💰");
    }
}
