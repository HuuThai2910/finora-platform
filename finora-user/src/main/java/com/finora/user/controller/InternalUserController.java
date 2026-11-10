package com.finora.user.controller;

import com.finora.user.dto.response.UserFullNameResponse;
import com.finora.user.dto.response.UserIdMatchResponse;
import com.finora.user.dto.response.UserIdentityResponse;
import com.finora.user.service.UserIdentityService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint nội bộ cho service khác gọi bằng token service account, không đi qua gateway công khai.
 * <p>
 * Quyền {@code user:identity:read_internal} là client role của {@code finora-user-client}, chỉ gán cho service account
 * {@code finora-investment-client} và {@code finora-loan-client}; không nằm trong role người dùng nào nên token người
 * dùng nhận 403.
 */
@RestController
@RequestMapping("/api/v1/internal/users")
@RequiredArgsConstructor
@Validated
public class InternalUserController {

    /** Số người tối đa một lần tra theo tên; chuỗi quá chung thì bên gọi chỉ lọc trên chừng này người. */
    static final int MAX_NAME_MATCHES = 200;

    private final UserIdentityService userIdentityService;

    /**
     * Họ tên và CCCD đã che (chỉ giữ 4 số cuối) của một người dùng, để Investment in lên giấy xác nhận chuyển
     * nhượng Note và Loan in lên hợp đồng vay. {@code userId} là giá trị Investment lưu làm investorId/buyer_id/seller_id,
     * Loan lưu làm borrower_id.
     */
    @GetMapping("/{userId}/identity")
    @PreAuthorize("hasAuthority('user:identity:read_internal')")
    public ResponseEntity<UserIdentityResponse> getIdentity(@PathVariable String userId) {
        return ResponseEntity.ok(userIdentityService.identityOf(userId));
    }

    /**
     * Họ tên của nhiều người dùng một lần ({@code ?userIds=1,2,...}, tối đa 100), để danh sách gọi vốn của Investment
     * hiện tên người vay mà không gọi từng dòng. ID không có hồ sơ bị bỏ qua; không trả CCCD.
     */
    @GetMapping("/full-names")
    @PreAuthorize("hasAuthority('user:identity:read_internal')")
    public ResponseEntity<List<UserFullNameResponse>> getFullNames(
            @RequestParam @NotEmpty @Size(max = 100) List<@Size(max = 100) String> userIds) {
        return ResponseEntity.ok(userIdentityService.fullNamesOf(userIds));
    }

    /**
     * ID của những người có họ tên chứa {@code q} (không phân biệt dấu, tối thiểu 2 ký tự), tối đa
     * {@link #MAX_NAME_MATCHES} người, để Investment lọc giao dịch chuyển nhượng và phần vốn theo tên hai bên mà không
     * phải giữ bản sao họ tên. Không trả họ tên hay CCCD.
     */
    @GetMapping("/ids-by-name")
    @PreAuthorize("hasAuthority('user:identity:read_internal')")
    public ResponseEntity<List<UserIdMatchResponse>> getIdsByName(
            @RequestParam @NotBlank @Size(min = 2, max = 100) String q) {
        return ResponseEntity.ok(userIdentityService.userIdsByName(q, MAX_NAME_MATCHES));
    }
}
