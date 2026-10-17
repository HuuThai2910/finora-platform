package com.finora.loan.integration.fineract.contract;

/**
 * Job lập lịch của Fineract theo {@code GET /jobs} (W3E, D57). FINORA chỉ đọc để giám sát job cuối ngày; mọi thay đổi
 * lịch chạy làm trong Fineract.
 *
 * @param jobId            id nội bộ của Fineract, dùng để đọc {@code /jobs/{jobId}/runhistory}
 * @param shortName        mã ổn định, ví dụ {@code LA_RINT}, {@code LA_ARAG}
 * @param displayName      tên hiển thị
 * @param active           cron của job có bật không; {@code false} thì job chỉ chạy khi có người gọi tay
 * @param currentlyRunning job đang chạy
 * @param lastRun          lần chạy gần nhất (thành công hoặc lỗi), {@code null} nếu chưa từng chạy
 */
public record FineractJob(
        long jobId,
        String shortName,
        String displayName,
        boolean active,
        boolean currentlyRunning,
        FineractJobRun lastRun
) {
}
