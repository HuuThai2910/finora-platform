package com.finora.loan.integration.fineract.client;

import com.finora.loan.domain.restructure.LoanRescheduleType;
import java.time.LocalDate;
import java.util.Optional;

public interface FineractRescheduleGateway {
    Optional<CoreReschedule> findByLoanAndMarker(Long fineractLoanId, String marker);
    CoreReschedule create(CreateRescheduleCommand command);
    CoreReschedule read(Long coreRescheduleId);
    void approve(Long coreRescheduleId, LocalDate approvedOnDate);

    record CreateRescheduleCommand(
            Long fineractLoanId,
            LoanRescheduleType requestType,
            LocalDate rescheduleFromDate,
            LocalDate adjustedDueDate,
            Integer extraTerms,
            long reasonId,
            String marker,
            LocalDate submittedOnDate) {}

    record CoreReschedule(Long id, String statusCode) {
        public boolean approved() {
            return statusCode != null && statusCode.toLowerCase().contains("approved");
        }
        public boolean rejected() {
            return statusCode != null && statusCode.toLowerCase().contains("rejected");
        }
    }
}

