package com.finora.loan.dto.restructure.request;

import jakarta.validation.constraints.Size;

public record AdminLoanRescheduleDecisionRequest(@Size(max = 500) String comment) {}

