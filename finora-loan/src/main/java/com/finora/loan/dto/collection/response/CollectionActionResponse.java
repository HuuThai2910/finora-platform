package com.finora.loan.dto.collection.response;

import com.finora.loan.domain.collection.CollectionActionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CollectionActionResponse(UUID actionId, CollectionActionType actionType, String note,
        LocalDate promiseDate, BigDecimal promiseAmount, String actorId, Instant createdAt) {}

