package com.finora.payment.controller;

import com.finora.payment.dto.request.CreateHoldRequest;
import com.finora.payment.dto.request.TransferRequest;
import com.finora.payment.dto.response.HoldResponse;
import com.finora.payment.dto.response.TransferResponse;
import com.finora.payment.service.hold.HoldTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private final HoldTransferService service;

    @PostMapping("/holds")
    public HoldResponse hold(@Valid @RequestBody CreateHoldRequest request) {
        return service.hold(request);
    }

    @PostMapping("/holds/{holdReference}/release")
    public HoldResponse release(
            @PathVariable String holdReference,
            @RequestParam String orderReference
    ) {
        return service.release(holdReference, orderReference);
    }

    @PostMapping("/transfers")
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return service.transfer(request);
    }
}
