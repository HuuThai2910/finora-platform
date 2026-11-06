package com.finora.loan.service.servicing.impl;

import com.finora.loan.domain.servicing.entity.*;
import com.finora.loan.domain.servicing.enums.*;
import com.finora.loan.domain.servicing.model.*;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import com.finora.loan.service.servicing.LoanServicingReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Kiểm tra bất biến identity/tài chính trước khi cho snapshot core ghi đè projection.
 *
 * <p>Số tiền lệch không quá 0,01 được coi là khớp.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanServicingReconciliationServiceImpl implements LoanServicingReconciliationService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private final LoanReconciliationIncidentRepository loanReconciliationIncidentRepository;

    @Override
    public List<ReconciliationIncidentType> inspect(
            FinoraLoan loan, LoanServicingSnapshot snapshot, Instant now) {
        List<Mismatch> mismatches = new ArrayList<>();
        addIfDifferent(mismatches, ReconciliationIncidentType.CORE_LOAN_ID_MISMATCH,
                loan.getFineractLoanId().toString(), String.valueOf(snapshot.coreLoanId()));
        addIfDifferent(mismatches, ReconciliationIncidentType.EXTERNAL_ID_MISMATCH,
                loan.getContractNumber(), snapshot.externalId());
        addIfMoneyDifferent(mismatches, ReconciliationIncidentType.PRINCIPAL_DISBURSED_MISMATCH,
                loan.getPrincipalAmount(), snapshot.principalDisbursed());
        BigDecimal componentTotal = snapshot.principalOutstanding()
                .add(snapshot.interestOutstanding())
                .add(snapshot.feeOutstanding())
                .add(snapshot.penaltyOutstanding());
        addIfMoneyDifferent(mismatches, ReconciliationIncidentType.OUTSTANDING_BREAKDOWN_MISMATCH,
                componentTotal, snapshot.totalOutstanding());

        if (mismatches.isEmpty()) {
            loanReconciliationIncidentRepository.findOpenByLoanIdForUpdate(loan.getId())
                    .forEach(incident -> incident.resolve("CORE_SNAPSHOT_VALIDATED", "SYSTEM", now));
            return List.of();
        }
        mismatches.forEach(mismatch -> loanReconciliationIncidentRepository
                .findOpenForUpdate(loan.getId(), mismatch.type())
                .ifPresentOrElse(
                        incident -> incident.observe(mismatch.expected(), mismatch.actual(), now),
                        () -> loanReconciliationIncidentRepository.save(LoanReconciliationIncident.open(
                                loan.getId(), loan.getLoanNumber(),
                                mismatch.type(), mismatch.expected(), mismatch.actual(), now))));
        return mismatches.stream().map(Mismatch::type).toList();
    }

    private static void addIfDifferent(List<Mismatch> target, ReconciliationIncidentType type,
            String expected, String actual) {
        if (expected == null || actual == null || !expected.equals(actual)) {
            target.add(new Mismatch(type, String.valueOf(expected), String.valueOf(actual)));
        }
    }

    private static void addIfMoneyDifferent(List<Mismatch> target, ReconciliationIncidentType type,
            BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null || expected.subtract(actual).abs().compareTo(TOLERANCE) > 0) {
            target.add(new Mismatch(type, String.valueOf(expected), String.valueOf(actual)));
        }
    }

    private record Mismatch(ReconciliationIncidentType type, String expected, String actual) {}
}
