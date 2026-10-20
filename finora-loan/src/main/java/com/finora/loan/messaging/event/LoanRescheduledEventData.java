package com.finora.loan.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finora.loan.domain.restructure.LoanRescheduleType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Contract liên service không mang reasonComment vì đó có thể là dữ liệu nhạy cảm tự do.
 *
 * @param nextDueDate   kỳ tiếp theo theo snapshot Fineract đọc sau khi lịch cơ cấu được duyệt (cùng snapshot với
 *                      {@code newMaturityDate}); field optional thêm sau (P3, D76), vắng mặt khi core không trả
 * @param nextDueAmount số tiền kỳ đó, chuỗi decimal VND hai chữ số thập phân; vắng mặt khi core không trả
 */
public record LoanRescheduledEventData(
        Long loanApplicationId,
        String loanNumber,
        String borrowerId,
        Long fineractLoanId,
        UUID requestId,
        LoanRescheduleType requestType,
        LocalDate previousMaturityDate,
        LocalDate newMaturityDate,
        LocalDate rescheduleFromDate,
        LocalDate adjustedDueDate,
        Integer extraTerms,
        Instant occurredAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate nextDueDate,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextDueAmount
) {}
