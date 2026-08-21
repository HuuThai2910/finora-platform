package com.finora.user.controller;

import com.finora.common.dto.PageResponse;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.security.SecurityUtils;
import com.finora.user.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller quản trị người dùng — chỉ dành cho admin.
 * <p>
 * Bao gồm: xem danh sách, khóa/mở khóa tài khoản, gán vai trò.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserProfileService userProfileService;

    @GetMapping
    @PreAuthorize("hasAuthority('user:admin:read_all')")
    public ResponseEntity<PageResponse<UserProfileResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(userProfileService.getAllUsers(PageRequest.of(page, size)));
    }

    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAuthority('user:admin:lock')")
    public ResponseEntity<Void> lockUser(@PathVariable Long id) {
        userProfileService.lockUser(id, SecurityUtils.getCurrentKeycloakUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAuthority('user:admin:lock')")
    public ResponseEntity<Void> unlockUser(@PathVariable Long id) {
        userProfileService.unlockUser(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:admin:assign_role')")
    public ResponseEntity<Void> assignRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {

        userProfileService.assignRole(id, body.get("role"), SecurityUtils.getCurrentKeycloakUserId());
        return ResponseEntity.ok().build();
    }
}
