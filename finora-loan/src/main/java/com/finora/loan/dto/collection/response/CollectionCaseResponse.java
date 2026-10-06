package com.finora.loan.dto.collection.response;

import com.finora.loan.domain.collection.CollectionCaseStatus;
import com.finora.loan.domain.collection.CollectionStage;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CollectionCaseResponse(UUID caseId, String loanNumber, String borrowerId,
        CollectionStage stage, CollectionCaseStatus status, int daysPastDue, int debtGroup,
        BigDecimal overdueAmount, BigDecimal totalOutstanding, LocalDate overdueSince,
        Instant openedAt, Instant lastObservedAt, Instant closedAt) {}

