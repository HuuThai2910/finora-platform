package com.finora.user.service;

import java.util.List;

/**
 * Hàng đợi task ánh xạ người dùng sang CIC: lấy task đến hạn, nhận task (khoá dòng + lease), ghi hoàn tất/lỗi.
 */
public interface CicMappingTaskService {

    /** ID các task đến hạn chạy, tối đa một lô theo cấu hình. */
    List<Long> dueIds();

    /** Nhận task để xử lý; {@code null} nếu task không còn hoặc chưa đến hạn (worker khác đã nhận). */
    ClaimedTask claim(Long taskId);

    void complete(Long taskId);

    /** Ghi lỗi: lỗi tạm thời và còn lượt thử thì hẹn chạy lại (chờ tăng dần), ngược lại task chuyển {@code DEAD}. */
    void fail(Long taskId, String errorCode, boolean retryable);

    /** Task vừa nhận cùng hồ sơ cần ánh xạ và số lần đã thử. */
    record ClaimedTask(Long taskId, Long userProfileId, int attemptCount) {
    }
}
