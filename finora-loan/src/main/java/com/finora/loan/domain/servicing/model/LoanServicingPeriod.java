package com.finora.loan.domain.servicing.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Một kỳ trả nợ chuẩn hóa đọc từ lịch hiện hành của Fineract. */
public record LoanServicingPeriod(
        Integer period,
        LocalDate fromDate,
        LocalDate dueDate,
        Integer daysInPeriod,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal fees,
        BigDecimal penalties,
        BigDecimal totalDue,
        BigDecimal outstandingBalance
) {}
