package com.finora.user.controller;

import com.finora.user.dto.request.StaffRoleRequest;
import com.finora.user.dto.response.OpsPermissionResponse;
import com.finora.user.dto.response.StaffRoleResponse;
import com.finora.user.service.StaffRoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Màn Phân quyền của web quản trị: danh mục quyền và CRUD vai trò nhân viên.
 * <p>
 * Mọi endpoint chỉ dành cho ADMIN, cố ý không mở bằng quyền {@code ops:*}: nhân viên có quyền cấu hình vai trò sẽ
 * tự nâng quyền được. Gateway chuyển {@code /api/v1/admin/staff-roles/**} về finora-user (trước route admin của
 * Loan).
 */
@RestController
@RequestMapping("/api/v1/admin/staff-roles")
@RequiredArgsConstructor
public class AdminStaffRoleController {

    private final StaffRoleService staffRoleService;

    /** Danh mục chức năng theo thứ tự hiển thị, mỗi chức năng có quyền Xem và (nếu có) Thao tác. */
    @GetMapping("/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OpsPermissionResponse>> getPermissionCatalog() {
        return ResponseEntity.ok(staffRoleService.permissionCatalog());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<StaffRoleResponse>> listStaffRoles() {
        return ResponseEntity.ok(staffRoleService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StaffRoleResponse> createStaffRole(@Valid @RequestBody StaffRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staffRoleService.create(request));
    }

    /** Sửa tên, mô tả, quyền; thành viên của vai trò bị đăng xuất để nhận quyền mới. */
    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StaffRoleResponse> updateStaffRole(@PathVariable String code,
                                                             @Valid @RequestBody StaffRoleRequest request) {
        return ResponseEntity.ok(staffRoleService.update(code, request));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteStaffRole(@PathVariable String code) {
        staffRoleService.delete(code);
        return ResponseEntity.noContent().build();
    }
}
