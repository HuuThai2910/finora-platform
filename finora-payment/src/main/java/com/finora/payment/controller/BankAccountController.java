package com.finora.payment.controller;

import com.finora.common.security.pin.PinScope;
import com.finora.common.security.pin.RequirePin;
import com.finora.common.security.ratelimit.RateLimited;
import com.finora.payment.dto.request.BankAccountInquiryRequest;
import com.finora.payment.dto.request.ConfirmBankLinkRequest;
import com.finora.payment.dto.request.StartBankLinkRequest;
import com.finora.payment.dto.response.BankAccountInquiryResponse;
import com.finora.payment.dto.response.BankAccountResponse;
import com.finora.payment.dto.response.BankLinkChallengeResponse;
import com.finora.payment.dto.response.BankResponse;
import com.finora.payment.service.bankaccount.BankAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Danh bạ ngân hàng và tài khoản ngân hàng liên kết của người dùng (BL-001). Bắt đầu liên kết và huỷ liên kết cần PIN
 * {@code BANK_ACCOUNT} gắn với đúng {@code Idempotency-Key} (D38); số tài khoản đầy đủ chỉ đi trong body request.
 */
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class BankAccountController {

    private final BankAccountService bankAccountService;

    @GetMapping("/banks")
    public ResponseEntity<List<BankResponse>> banks() {
        return ResponseEntity.ok(bankAccountService.banks());
    }

    @GetMapping("/me/bank-accounts")
    public ResponseEntity<List<BankAccountResponse>> list() {
        return ResponseEntity.ok(bankAccountService.list());
    }

    /** Mỗi lần tra cứu là một lời gọi ngân hàng: chặn dò hàng loạt số tài khoản để đọc tên chủ tài khoản. */
    @PostMapping("/me/bank-accounts/inquiry")
    @RateLimited(value = "bank-account.inquiry", limit = 20, window = "PT1H")
    public ResponseEntity<BankAccountInquiryResponse> inquire(@Valid @RequestBody BankAccountInquiryRequest request) {
        return ResponseEntity.ok(bankAccountService.inquire(request));
    }

    @PostMapping("/me/bank-accounts")
    @RequirePin(PinScope.BANK_ACCOUNT)
    @RateLimited(value = "bank-account.link", limit = 10, window = "PT1H")
    public ResponseEntity<BankLinkChallengeResponse> startLink(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody StartBankLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bankAccountService.startLink(request, idempotencyKey));
    }

    @PostMapping("/me/bank-accounts/link-requests/{linkRequestId}/resend-otp")
    public ResponseEntity<BankLinkChallengeResponse> resendOtp(@PathVariable UUID linkRequestId) {
        return ResponseEntity.ok(bankAccountService.resendOtp(linkRequestId));
    }

    @PostMapping("/me/bank-accounts/link-requests/{linkRequestId}/confirm")
    @RateLimited(value = "bank-account.confirm", limit = 30, window = "PT1H")
    public ResponseEntity<BankAccountResponse> confirm(@PathVariable UUID linkRequestId,
            @Valid @RequestBody ConfirmBankLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bankAccountService.confirm(linkRequestId, request));
    }

    @PutMapping("/me/bank-accounts/{bankAccountId}/default")
    public ResponseEntity<BankAccountResponse> setDefault(@PathVariable UUID bankAccountId) {
        return ResponseEntity.ok(bankAccountService.setDefault(bankAccountId));
    }

    @DeleteMapping("/me/bank-accounts/{bankAccountId}")
    @RequirePin(PinScope.BANK_ACCOUNT)
    public ResponseEntity<Void> remove(@PathVariable UUID bankAccountId) {
        bankAccountService.remove(bankAccountId);
        return ResponseEntity.noContent().build();
    }
}
