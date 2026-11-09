package com.finora.user.controller;

import com.finora.user.dto.request.CreateStaffAccountRequest;
import com.finora.user.dto.response.StaffAccountCreatedResponse;
import com.finora.user.service.StaffAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tạo tài khoản nội bộ (nhân viên, quản trị viên) ở trang Phân quyền.
 * <p>
 * Dùng chung đường dẫn tập hợp {@code /api/v1/admin/users} với {@link AdminUserController} (POST lên tập hợp là tạo
 * phần tử mới) để API Gateway không phải thêm route. Chỉ ADMIN: không mở bằng quyền nhân viên để không ai tự tạo tài
 * khoản quyền cao hơn mình.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminStaffAccountController {

    private final StaffAccountService staffAccountService;

    /**
     * Body {@code {"fullName":"...","email":"...","phone":"09...","role":"STAFF","staffRoleCode":"STAFF_VAN_HANH"}};
     * trả {@code 201} kèm {@code temporaryPassword} — chỉ trả đúng một lần, không có API đọc lại. Cấm cache phản hồi.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StaffAccountCreatedResponse> createStaffAccount(
            @Valid @RequestBody CreateStaffAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(staffAccountService.createStaffAccount(request));
    }
}
