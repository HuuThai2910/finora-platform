package com.finora.blockchain.controller;

import com.finora.common.dto.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API cho Blockchain Explorer.
 * TODO: Implement tra cứu lịch sử giao dịch, đối chiếu hash toàn vẹn.
 */
@RestController
@RequestMapping("/api/v1/blockchain")
@RequiredArgsConstructor
public class BlockchainController {

    @GetMapping("/health")
    public BaseResponse<String> healthCheck() {
        return BaseResponse.success("FINORA Blockchain Service is running! ⛓️");
    }
}
