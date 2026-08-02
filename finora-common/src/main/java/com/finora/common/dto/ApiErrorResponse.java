package com.finora.common.dto;

/**
 * Hợp đồng lỗi dùng chung tại biên HTTP.
 *
 * @param code mã lỗi ổn định để client xử lý, dùng UPPER_SNAKE_CASE
 * @param message thông báo an toàn, dễ hiểu cho người dùng
 * @param details chi tiết đã lọc; không chứa stack trace, SQL hoặc dữ liệu nhạy cảm
 * @param traceId mã đối chiếu response với log của hệ thống
 */
public record ApiErrorResponse(
        String code,
        String message,
        Object details,
        String traceId
) {
}
