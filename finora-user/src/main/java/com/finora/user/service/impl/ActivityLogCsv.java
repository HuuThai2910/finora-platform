package com.finora.user.service.impl;

import com.finora.user.dto.response.ActivityLogEntryResponse;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Ghi nhật ký hoạt động thành CSV cho người quản trị mở bằng Excel: có BOM UTF-8 để giữ dấu tiếng Việt, giờ theo
 * Việt Nam. Không xuất thân request (đã che nhưng vẫn là dữ liệu người dùng gửi lên), chỉ các cột trên bảng.
 */
final class ActivityLogCsv {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final String HEADER = "Thời điểm,Thao tác,Đường dẫn,Tham số,Dịch vụ,Mã trạng thái,Thời gian xử lý (ms),"
            + "Người gọi,Vai trò,Ứng dụng,IP,Mã lỗi,Câu lỗi,Mã tra cứu";

    private ActivityLogCsv() {
    }

    static String write(List<ActivityLogEntryResponse> rows) {
        StringBuilder out = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (ActivityLogEntryResponse row : rows) {
            out.append(String.join(",",
                    cell(row.getAt() == null ? null : TIME.format(row.getAt())),
                    cell(row.getMethod()),
                    cell(row.getPath()),
                    cell(row.getQuery()),
                    cell(row.getService()),
                    cell(String.valueOf(row.getStatus())),
                    cell(String.valueOf(row.getDurationMs())),
                    cell(row.getUser()),
                    cell(row.getRole()),
                    cell(row.getClient()),
                    cell(row.getIp()),
                    cell(row.getErrorCode()),
                    cell(row.getErrorMessage()),
                    cell(row.getTraceId())))
                    .append("\r\n");
        }
        return out.toString();
    }

    /**
     * Một ô CSV theo RFC 4180: bọc nháy kép khi có dấu phẩy, nháy hoặc xuống dòng. Ô bắt đầu bằng {@code = + - @} được
     * thêm nháy đơn để Excel không chạy như công thức (CSV injection).
     */
    static String cell(String value) {
        if (value == null) return "";
        String text = value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        boolean quote = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
        return quote ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }
}
