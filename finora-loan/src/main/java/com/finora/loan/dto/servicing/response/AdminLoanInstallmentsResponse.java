package com.finora.loan.dto.servicing.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Trạng thái từng kỳ trả của một khoản vay cho màn quản trị, đọc từ lịch hiện hành trên Fineract (sau cơ cấu là lịch
 * mới, không phải lịch trong hợp đồng).
 *
 * @param businessDate ngày nghiệp vụ (giờ Việt Nam) dùng để xếp kỳ quá hạn/chưa đến hạn
 * @param paidCount    số kỳ đã trả đủ
 * @param overdueCount số kỳ đã qua hạn mà chưa trả đủ
 */
public record AdminLoanInstallmentsResponse(
        String loanNumber,
        LocalDate businessDate,
        int paidCount,
        int overdueCount,
        List<Installment> installments
) {

    /** Trạng thái một kỳ: PAID đã trả đủ, OVERDUE quá hạn chưa trả đủ, DUE đến hạn hôm nay, UPCOMING chưa đến hạn. */
    public enum InstallmentStatus {
        PAID,
        OVERDUE,
        DUE,
        UPCOMING
    }

    /**
     * @param paidOn    ngày trả đủ kỳ; null khi chưa trả đủ
     * @param totalPaid số đã trả cho kỳ, có thể là một phần
     */
    public record Installment(
            int number,
            LocalDate dueDate,
            BigDecimal totalDue,
            BigDecimal totalPaid,
            LocalDate paidOn,
            InstallmentStatus status
    ) {
    }
}
