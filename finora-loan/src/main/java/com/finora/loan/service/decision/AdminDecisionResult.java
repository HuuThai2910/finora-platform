package com.finora.loan.service.decision;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;

public record AdminDecisionResult(LoanApplication application, LoanContract contract) {
}
