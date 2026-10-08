package com.finora.user.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.user.dto.request.AssignRoleRequest;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import com.finora.user.dto.response.UserStatsSeriesResponse;
import com.finora.user.service.UserProfileService;
import com.finora.user.service.UserStatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;


/**
 * Controller quản trị người dùng — chỉ dành cho admin.
 * <p>
 * Bao gồm: xem danh sách, xem chi tiết, khóa/mở khóa tài khoản, gán vai trò.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserProfileService userProfileService;
    private final UserStatisticsService userStatisticsService;

    /**
     * Danh sách người dùng — lọc theo vai trò và trạng thái eKYC ở tầng DB.
     * <p>
     * Bỏ trống (hoặc {@code ALL}) là không lọc. Lọc ở đây thay vì ở client để
     * kết quả trải trên toàn bộ dữ liệu chứ không chỉ trang đang tải.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('user:admin:read_all')")
    public ResponseEntity<PageResponse<UserProfileResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String ekycStatus) {

        return ResponseEntity.ok(
                userProfileService.getAllUsers(role, ekycStatus, PageRequest.of(page, size)));
    }

    /** Thống kê tổng số người dùng theo vai trò và trạng thái eKYC. */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('user:admin:read_all')")
    public ResponseEntity<UserStatsResponse> getUserStats() {
        return ResponseEntity.ok(userProfileService.getUserStats());
    }

    /**
     * Người dùng mới và eKYC hoàn tất theo cột thời gian (STATS-001).
     * <p>
     * Tham số nhận dạng chuỗi rồi tự đọc: nếu để Spring tự chuyển kiểu thì {@code bucket=YEAR} hoặc ngày
     * sai định dạng rơi vào handler lỗi chung và thành 500, trong khi đây là lỗi đầu vào của người gọi.
     *
     * @param from   ngày bắt đầu {@code YYYY-MM-DD}, bỏ trống là 30 ngày gần nhất
     * @param to     ngày kết thúc {@code YYYY-MM-DD}, bỏ trống là hôm nay theo giờ Việt Nam
     * @param bucket {@code DAY} (mặc định), {@code WEEK} hoặc {@code MONTH}
     */
    @GetMapping("/stats/series")
    @PreAuthorize("hasAuthority('user:admin:read_all')")
    public ResponseEntity<UserStatsSeriesResponse> getUserStatsSeries(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String bucket) {
        return ResponseEntity.ok(userStatisticsService.series(
                StatisticsPeriod.parseDate(from, "from"),
                StatisticsPeriod.parseDate(to, "to"),
                StatisticsBucket.parse(bucket)));
    }

    /** Chi tiết hồ sơ một người dùng — màn hình khách hàng/eKYC của admin. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:admin:read_all')")
    public ResponseEntity<UserProfileResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userProfileService.getUserById(id));
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

    /**
     * Gán vai trò mới.
     * <p>
     * Nhận vai trò qua body {@code {"role":"ADMIN"}} — cũng chấp nhận query
     * param {@code ?role=ADMIN} cho các client đang gửi theo kiểu cũ.
     */
    @PostMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('user:admin:assign_role')")
    public ResponseEntity<Void> assignRole(
            @PathVariable Long id,
            @RequestParam(value = "role", required = false) String roleParam,
            @RequestBody(required = false) AssignRoleRequest body) {

        String role = (body != null && body.getRole() != null && !body.getRole().isBlank())
                ? body.getRole()
                : roleParam;

        userProfileService.assignRole(id, role, SecurityUtils.getCurrentKeycloakUserId());
        return ResponseEntity.ok().build();
    }


}
