package com.finora.notification.service.reminder;

import java.time.LocalDate;

/**
 * Nhắc kỳ đến hạn (D64): trước {@code offsetsDays} ngày (mặc định 3 ngày và đúng ngày đến hạn) theo ngày nghiệp vụ Việt
 * Nam. Không nhắc kỳ mà người vay đã trả trước và Payment đang giữ (D16).
 */
public interface DueReminderService {

    /** @return số lời nhắc mới tạo cho ngày nghiệp vụ {@code today} */
    int remind(LocalDate today);
}
