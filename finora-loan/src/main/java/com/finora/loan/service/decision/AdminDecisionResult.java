package com.finora.loan.service.decision;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.LoanContract;

public record AdminDecisionResult(LoanApplication application, LoanContract contract) {
}
