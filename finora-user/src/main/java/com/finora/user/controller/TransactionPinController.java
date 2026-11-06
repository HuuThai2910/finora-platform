package com.finora.user.controller;

import com.finora.common.security.SecurityUtils;
import com.finora.user.dto.request.ChangePinRequest;
import com.finora.user.dto.request.ResetPinRequest;
import com.finora.user.dto.request.SetPinRequest;
import com.finora.user.dto.request.VerifyPinRequest;
import com.finora.user.dto.response.PinStatusResponse;
import com.finora.user.dto.response.PinTokenResponse;
import com.finora.user.service.TransactionPinService.ClientInfo;
import com.finora.user.service.TransactionPinService;
import com.finora.user.util.HttpRequestUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mã PIN giao dịch của người dùng đang đăng nhập.
 * <p>
 * Mobile gọi {@code verify} ngay trước thao tác nhạy cảm, rồi gửi pin-token nhận
 * được ở header {@code X-Pin-Token} sang service nghiệp vụ.
 */
@RestController
@RequestMapping("/api/v1/users/me/pin")
@RequiredArgsConstructor
public class TransactionPinController {

    private final TransactionPinService transactionPinService;

    @GetMapping
    public ResponseEntity<PinStatusResponse> status() {
        return ResponseEntity.ok(transactionPinService.status(SecurityUtils.getCurrentKeycloakUserId()));
    }

    @PostMapping
    public ResponseEntity<PinStatusResponse> create(@Valid @RequestBody SetPinRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionPinService.create(SecurityUtils.getCurrentKeycloakUserId(), request.getPin()));
    }

    @PostMapping("/verify")
    public ResponseEntity<PinTokenResponse> verify(@Valid @RequestBody VerifyPinRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(transactionPinService.verify(SecurityUtils.getCurrentKeycloakUserId(),
                request.getPin(), request.getScope(), request.getRequestKey(), client(http)));
    }

    @PutMapping
    public ResponseEntity<PinStatusResponse> change(@Valid @RequestBody ChangePinRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(transactionPinService.change(SecurityUtils.getCurrentKeycloakUserId(),
                request.getCurrentPin(), request.getNewPin(), client(http)));
    }

    @PostMapping("/reset")
    public ResponseEntity<PinStatusResponse> reset(@Valid @RequestBody ResetPinRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(transactionPinService.reset(SecurityUtils.getCurrentKeycloakUserId(),
                request.getPassword(), request.getNewPin(), client(http)));
    }

    private static ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(HttpRequestUtils.extractIpAddress(http), HttpRequestUtils.extractUserAgent(http));
    }
}
