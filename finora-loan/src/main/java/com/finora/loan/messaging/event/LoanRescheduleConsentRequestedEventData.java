package com.finora.loan.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Đề nghị Investment chụp danh sách chủ Note hiện tại và lấy đồng ý trước khi đổi lịch. */
public record LoanRescheduleConsentRequestedEventData(
        UUID requestId,
        Long loanApplicationId,
        String loanNumber,
        String requestType,
        LocalDate rescheduleFromDate,
        LocalDate adjustedDueDate,
        Integer extraTerms,
        LocalDate originalMaturityDate,
        Instant requestedAt
) {}
