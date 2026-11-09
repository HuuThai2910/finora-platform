package com.finora.user.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.enums.user.Gender;
import com.finora.common.security.SecurityUtils;
import com.finora.common.statistics.StatisticsBucket;
import com.finora.common.statistics.StatisticsPeriod;
import com.finora.user.dto.request.AssignRoleRequest;
import com.finora.user.dto.request.LockUserRequest;
import com.finora.user.dto.request.UserListFilter;
import com.finora.user.dto.request.UserSearchCriteria;
import com.finora.user.dto.response.UserProfileResponse;
import com.finora.user.dto.response.UserStatsResponse;
import com.finora.user.dto.response.UserStatsSeriesResponse;
import com.finora.user.service.UserProfileService;
import com.finora.user.service.UserStatisticsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;


/**
 * Controller quản trị người dùng.
 * <p>
 * Bao gồm: xem danh sách, xem chi tiết, khóa/mở khóa tài khoản, gán vai trò. Xem và khóa mở theo quyền nhân viên
 * {@code ops:customer:*} (thống kê còn mở cho {@code ops:dashboard:view}); authority {@code user:admin:*} giữ lại
 * cho service account (finora-ai đọc hồ sơ bằng {@code user:admin:read_all}). Gán vai trò chỉ ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserProfileService userProfileService;
    private final UserStatisticsService userStatisticsService;

    /**
     * Danh sách người dùng — lọc theo vai trò, trạng thái eKYC, vai trò nhân viên và chuỗi tìm ở tầng DB.
     * <p>
     * Bỏ trống (hoặc {@code ALL}) là không lọc. Lọc ở đây thay vì ở client để
     * kết quả trải trên toàn bộ dữ liệu chứ không chỉ trang đang tải.
     *
     * @param q             chuỗi tìm: một phần họ tên (không phân biệt dấu) hoặc email, hoặc đúng số điện thoại / CCCD
     * @param staffRoleCode mã vai trò nhân viên; có giá trị thì chỉ trả tài khoản nhân viên mang vai trò đó
     * @param scope         nhóm tài khoản: {@code STAFF} (quản trị viên + nhân viên — trang Phân quyền) hoặc
     *                      {@code CUSTOMER} (người vay + nhà đầu tư); bỏ trống là mọi tài khoản
     * @param locked        {@code true}/{@code false}: chỉ tài khoản đang khóa / đang hoạt động
     * @param ageMin        tuổi theo ngày sinh eKYC (cùng {@code ageMax}); {@code gender} theo eKYC
     * @param lastLoginFrom lần đăng nhập gần nhất trên mọi thiết bị (cùng {@code lastLoginTo})
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:customer:view','ROLE_ADMIN')")
    public ResponseEntity<PageResponse<UserProfileResponse>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String ekycStatus,
            @RequestParam(required = false) String staffRoleCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createdTo,
            @RequestParam(required = false) Boolean locked,
            @RequestParam(required = false) Integer ageMin,
            @RequestParam(required = false) Integer ageMax,
            @RequestParam(required = false) Gender gender,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastLoginFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastLoginTo) {

        // Bảng lọc nâng cao: ngày theo giờ Việt Nam (gồm cả hai đầu); khoảng sai trả 400 FILTER_RANGE_INVALID.
        UserListFilter filter = new UserListFilter(createdFrom, createdTo, locked, ageMin, ageMax, gender,
                lastLoginFrom, lastLoginTo);
        return ResponseEntity.ok(userProfileService.searchUsers(
                new UserSearchCriteria(role, ekycStatus, staffRoleCode, q, scope, filter),
                PageRequest.of(Math.max(page, 0), size)));
    }

    /** Thống kê tổng số người dùng theo vai trò và trạng thái eKYC. */
    @GetMapping("/stats")
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:customer:view','ops:dashboard:view','ROLE_ADMIN')")
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
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:customer:view','ops:dashboard:view','ROLE_ADMIN')")
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
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:customer:view','ROLE_ADMIN')")
    public ResponseEntity<UserProfileResponse> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userProfileService.getUserById(id));
    }

    /**
     * Khóa tài khoản: body {@code {"reason":"..."}} bắt buộc. Khóa xong mọi phiên đang mở của người dùng bị đăng xuất
     * (Keycloak thu hồi refresh token); access token đã cấp vẫn hợp lệ tới khi hết hạn.
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAnyAuthority('user:admin:lock','ops:customer:action','ROLE_ADMIN')")
    public ResponseEntity<Void> lockUser(@PathVariable Long id, @Valid @RequestBody LockUserRequest request) {
        userProfileService.lockUser(id, request.reason(), SecurityUtils.getCurrentKeycloakUserId());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/unlock")
    @PreAuthorize("hasAnyAuthority('user:admin:lock','ops:customer:action','ROLE_ADMIN')")
    public ResponseEntity<Void> unlockUser(@PathVariable Long id) {
        userProfileService.unlockUser(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Gán vai trò mới — chỉ ADMIN, không mở bằng quyền nhân viên để không ai tự nâng quyền.
     * <p>
     * Chỉ cho tài khoản nội bộ: đổi qua lại STAFF/ADMIN hoặc đổi vai trò nhân viên. Khách hàng không được biến thành
     * nhân viên ({@code 409 CUSTOMER_ROLE_LOCKED}) và nhân viên không được hạ thành khách
     * ({@code 400 ROLE_NOT_ASSIGNABLE}); nhân viên mới tạo qua {@code POST /api/v1/admin/users}.
     * <p>
     * Nhận vai trò qua body {@code {"role":"STAFF","staffRoleCode":"STAFF_VAN_HANH"}} — cũng chấp nhận query
     * param {@code ?role=ADMIN} cho các client đang gửi theo kiểu cũ. {@code staffRoleCode} bắt buộc khi
     * {@code role=STAFF} và bị từ chối với vai trò khác.
     */
    @PostMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> assignRole(
            @PathVariable Long id,
            @RequestParam(value = "role", required = false) String roleParam,
            @RequestBody(required = false) AssignRoleRequest body) {

        String role = (body != null && body.getRole() != null && !body.getRole().isBlank())
                ? body.getRole()
                : roleParam;

        String staffRoleCode = body != null ? body.getStaffRoleCode() : null;
        userProfileService.assignRole(id, role, staffRoleCode, SecurityUtils.getCurrentKeycloakUserId());
        return ResponseEntity.ok().build();
    }


}
