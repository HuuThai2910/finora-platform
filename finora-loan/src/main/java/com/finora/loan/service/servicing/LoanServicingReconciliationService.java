package com.finora.loan.service.servicing;

import com.finora.loan.domain.servicing.*;
import com.finora.loan.repository.servicing.LoanReconciliationIncidentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Kiểm tra bất biến identity/tài chính trước khi cho snapshot core ghi đè projection. */
@Service
@RequiredArgsConstructor
public class LoanServicingReconciliationService {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private final LoanReconciliationIncidentRepository incidents;

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
            incidents.findOpenByLoanIdForUpdate(loan.getId())
                    .forEach(value -> value.resolve("CORE_SNAPSHOT_VALIDATED", "SYSTEM", now));
            return List.of();
        }
        mismatches.forEach(mismatch -> incidents.findOpenForUpdate(loan.getId(), mismatch.type())
                .ifPresentOrElse(
                        value -> value.observe(mismatch.expected(), mismatch.actual(), now),
                        () -> incidents.save(LoanReconciliationIncident.open(loan.getId(), loan.getLoanNumber(),
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
