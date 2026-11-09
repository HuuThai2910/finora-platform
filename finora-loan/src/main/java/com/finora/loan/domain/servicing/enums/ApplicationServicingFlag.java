package com.finora.loan.domain.servicing.enums;

/**
 * Việc vận hành đang mở của khoản vay thuộc một hồ sơ, dùng làm bộ lọc danh sách hồ sơ quản trị và nhãn cảnh báo trên
 * từng dòng.
 *
 * <ul>
 *   <li>{@code OVERDUE}: khoản vay có hồ sơ thu hồi nợ đang mở (đang quá hạn).</li>
 *   <li>{@code RESCHEDULE_PENDING}: có yêu cầu cơ cấu chờ thẩm định.</li>
 *   <li>{@code INCIDENT}: có sai lệch đối soát đang mở, số liệu lưu đã cũ so với hệ thống lõi, hoặc saga giải ngân
 *   chờ vận hành ghi lại hệ thống lõi. Sự cố tiền ở Payment không nằm trong cờ này (khác database).</li>
 * </ul>
 */
public enum ApplicationServicingFlag {
    OVERDUE,
    RESCHEDULE_PENDING,
    INCIDENT
}
