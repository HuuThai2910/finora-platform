package com.finora.loan.service.contract;

import com.finora.loan.domain.contract.entity.LoanContract;

public record ContractConsentResult(LoanContract contract, boolean expiredDuringRequest) {
}
