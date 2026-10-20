package com.finora.loan.service.operations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Kết quả đánh giá độ tươi của job cuối ngày Fineract tại một thời điểm (W3E, D57).
 *
 * @param code       kết luận tổng; quyết định trạng thái health
 * @param message    câu giải thích tiếng Việt cho vận hành, không chứa dữ liệu khách hàng
 * @param staleAfter ngưỡng đang áp dụng
 * @param checkedAt  lần đọc Fineract thành công gần nhất, {@code null} nếu chưa đọc được lần nào
 * @param checkError mã lỗi của lần đọc gần nhất nếu lần đó lỗi (số liệu job khi đó là số của lần đọc thành công trước)
 * @param jobs       trạng thái từng job bắt buộc theo thứ tự cấu hình
 */
public record FineractEndOfDayStatus(
        Code code,
        String message,
        Duration staleAfter,
        Instant checkedAt,
        String checkError,
        List<JobFreshness> jobs
) {

    public FineractEndOfDayStatus {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    public enum Code {
        /** Mọi job bắt buộc có lần chạy thành công trong ngưỡng. */
        FRESH,
        /** Có job chưa từng chạy thành công hoặc lần thành công gần nhất cũ hơn ngưỡng: DPD/nợ quá hạn có thể sai. */
        STALE,
        /** Fineract không có job bắt buộc: sai tenant/phiên bản, hoặc job bị đổi tên. */
        JOB_MISSING,
        /** Không đọc được lịch sử để tìm lần thành công trước lần chạy lỗi gần nhất. */
        HISTORY_UNAVAILABLE,
        /** Chưa đọc được Fineract lần nào (lỗi kết nối, thiếu quyền, thiếu mật khẩu API). */
        CHECK_FAILED,
        /** Chưa tới lượt kiểm tra đầu tiên sau khi khởi động. */
        NOT_CHECKED,
        /** Kiểm tra không áp dụng: đã tắt, hoặc Loan ghi khoản vay vào provider mock thay vì Fineract. */
        NOT_APPLICABLE
    }

    /** Trạng thái một job. */
    public enum JobState {
        FRESH,
        STALE,
        NEVER_SUCCEEDED,
        MISSING,
        HISTORY_UNAVAILABLE
    }

    /**
     * @param shortName      mã job, ví dụ {@code LA_ARAG}
     * @param state          kết luận cho job
     * @param active         cron của job đang bật; {@code false} nghĩa là chỉ chạy khi có người gọi tay
     * @param lastRunStatus  trạng thái lần chạy gần nhất ({@code success}/{@code failed}), {@code null} nếu chưa chạy
     * @param lastRunAt      lúc kết thúc lần chạy gần nhất
     * @param lastSuccessAt  lúc kết thúc lần chạy thành công gần nhất
     * @param lastSuccessAge tuổi của lần thành công gần nhất tính theo giờ thật lúc đánh giá
     */
    public record JobFreshness(
            String shortName,
            JobState state,
            boolean active,
            String lastRunStatus,
            Instant lastRunAt,
            Instant lastSuccessAt,
            Duration lastSuccessAge
    ) {
    }
}
