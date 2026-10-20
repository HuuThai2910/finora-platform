package com.finora.investment.dto.request;

import jakarta.validation.constraints.Size;

public record InvestorRescheduleDecisionRequest(@Size(max = 500) String comment) {}
