package com.finora.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Thống kê số lượng người dùng toàn hệ thống — phục vụ bộ đếm trên tab lọc.
 * <p>
 * Đếm ở tầng DB nên con số không phụ thuộc vào trang đang xem.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserStatsResponse {

    /** Tổng số người dùng */
    private long total;

    /** Số lượng theo vai trò, khóa là tên {@code UserRole} (BORROWER, INVESTOR, ADMIN) */
    private Map<String, Long> byRole;

    /** Số lượng theo trạng thái eKYC, khóa là tên {@code EkycStatus} */
    private Map<String, Long> byEkycStatus;
}
