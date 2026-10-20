package com.finora.loan.messaging.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Đề nghị Investment chụp danh sách chủ Note hiện tại và lấy đồng ý trước khi đổi lịch.
 *
 * @param borrowerId ID logic của người vay (claim {@code user_id}); field optional thêm sau (P2, W3F) để Notification báo
 *                   người vay yêu cầu đã qua thẩm định, consumer cũ bỏ qua. Không mang lý do tự do hay PII
 */
public record LoanRescheduleConsentRequestedEventData(
        UUID requestId,
        Long loanApplicationId,
        String loanNumber,
        String requestType,
        LocalDate rescheduleFromDate,
        LocalDate adjustedDueDate,
        Integer extraTerms,
        LocalDate originalMaturityDate,
        Instant requestedAt,
        String borrowerId
) {}
