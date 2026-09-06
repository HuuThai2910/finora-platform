package com.finora.loan.domain.application;

/** Nguồn quyết định cuối để phân biệt quyết định tự động theo policy và quyết định của thẩm định viên. */
public enum LoanDecisionSource {
    AI_POLICY,
    ADMIN
}
