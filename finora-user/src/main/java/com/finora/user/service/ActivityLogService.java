package com.finora.user.service;
import com.finora.user.dto.response.ActivityLogPageResponse;

import java.time.Instant;

/** Nhật ký hoạt động của web quản trị: lượt gọi API qua Gateway, đọc từ Azure Log Analytics. */
public interface ActivityLogService {

    /**
     * @param range {@code 1h}, {@code 24h} (mặc định), {@code 7d}, {@code 30d}
     * @param method {@code GET}, {@code POST}… hoặc {@code WRITE} cho mọi thao tác thêm/sửa/xóa
     * @param outcome {@code SUCCESS}, {@code ERROR}, {@code CLIENT_ERROR}, {@code SERVER_ERROR}
     * @param service route id của Gateway
     * @param client {@code WEB}, {@code MOBILE}, {@code OTHER}
     * @param q tìm trong đường dẫn, người gọi, mã và câu lỗi, tham số, mã đối chiếu
     */
    ActivityLogPageResponse search(String range, String method, String outcome, String service, String client,
            String q, int page, int size);

    /**
     * Như trên, thêm khoảng tự chọn ({@code from}–{@code to}, tối đa 31 ngày, thay cho {@code range}) và cờ giữ các
     * lượt gọi tự động ({@code includeBackground}, mặc định ẩn).
     */
    ActivityLogPageResponse search(ActivityLogQuery query, int page, int size);

    /** Mọi dòng khớp bộ lọc (tối đa {@code maxRows}), mới nhất trước, dạng CSV UTF-8 có BOM để Excel đọc đúng dấu. */
    String exportCsv(ActivityLogQuery query);

    /** Bộ lọc đầy đủ của trang nhật ký. */
    record ActivityLogQuery(String range, String method, String outcome, String service, String client, String q,
            Instant from, Instant to, boolean includeBackground) {
    }
}
