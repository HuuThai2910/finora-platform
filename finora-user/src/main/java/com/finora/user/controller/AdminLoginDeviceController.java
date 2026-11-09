package com.finora.user.controller;

import com.finora.user.dto.response.LoginDeviceResponse;
import com.finora.user.service.LoginDeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Thiết bị đăng nhập của một khách hàng cho nhân viên hỗ trợ — cùng quyền Xem với trang chi tiết khách hàng. */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminLoginDeviceController {

    private final LoginDeviceService loginDeviceService;

    @GetMapping("/{id}/devices")
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:customer:view','ROLE_ADMIN')")
    public ResponseEntity<List<LoginDeviceResponse>> devices(@PathVariable Long id) {
        return ResponseEntity.ok(loginDeviceService.devicesOf(id));
    }
}
