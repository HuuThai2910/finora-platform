package com.finora.payment.controller;

import com.finora.payment.dto.request.CreateHoldRequest;
import com.finora.payment.dto.request.SettleHoldRequest;
import com.finora.payment.dto.request.TransferRequest;
import com.finora.payment.dto.response.HoldResponse;
import com.finora.payment.dto.response.TransferResponse;
import com.finora.payment.service.hold.HoldTransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final HoldTransferService holdTransferService;

    @PostMapping("/holds")
    public ResponseEntity<HoldResponse> hold(@Valid @RequestBody CreateHoldRequest request) {
        return ResponseEntity.ok(holdTransferService.hold(request));
    }

    /** R3: đọc khoản giữ theo mã lệnh, không đổi gì; 404 khi chưa từng giữ tiền cho lệnh này. */
    @GetMapping("/holds")
    public ResponseEntity<HoldResponse> findHold(@RequestParam String orderReference) {
        return ResponseEntity.ok(holdTransferService.findByOrderReference(orderReference));
    }

    @PostMapping("/holds/{holdReference}/release")
    public ResponseEntity<HoldResponse> release(
            @PathVariable String holdReference,
            @RequestParam String orderReference
    ) {
        return ResponseEntity.ok(holdTransferService.release(holdReference, orderReference));
    }

    /** Thanh toán một lần khớp trên sổ lệnh Notes từ khoản giữ của lệnh mua (service account Investment). */
    @PostMapping("/holds/{holdReference}/settlements")
    public ResponseEntity<TransferResponse> settle(
            @PathVariable String holdReference,
            @Valid @RequestBody SettleHoldRequest request
    ) {
        return ResponseEntity.ok(holdTransferService.settleFromHold(holdReference, request));
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(holdTransferService.transfer(request));
    }
}
