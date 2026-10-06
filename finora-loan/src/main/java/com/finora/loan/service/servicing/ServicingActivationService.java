package com.finora.loan.service.servicing;

import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Tạo khoản vay servicing trong cùng transaction hoàn tất Disbursement Saga. */
@Service
@RequiredArgsConstructor
public class ServicingActivationService {

    private final FinoraLoanRepository loanRepository;
    private final LoanServicingProjectionRepository projectionRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final ScheduleCalculationSnapshotMapper scheduleMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public FinoraLoan activate(
            LoanApplication application,
            LoanContract contract,
            Long fineractLoanId,
            Instant disbursedAt
    ) {
        FinoraLoan existing = loanRepository.findByLoanApplicationId(application.getId()).orElse(null);
        if (existing != null) {
            requireSame(existing, contract, fineractLoanId);
            return existing;
        }

        FinoraLoan loan = loanRepository.saveAndFlush(FinoraLoan.activate(
                loanNumber(application.getApplicationNumber()),
                application.getId(),
                application.getApplicationNumber(),
                contract.getContractNumber(),
                application.getBorrowerId(),
                fineractLoanId,
                contract.getPrincipalAmount(),
                "VND",
                disbursedAt));

        ScheduleCalculationSnapshot snapshot = scheduleRepository.findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy lịch Fineract đã dùng để lập hợp đồng"));
        List<SchedulePeriodResponse> periods = scheduleMapper.periods(snapshot).stream()
                .filter(period -> period.period() != null && period.period() > 0)
                .sorted(Comparator.comparing(SchedulePeriodResponse::period))
                .toList();
        SchedulePeriodResponse first = periods.isEmpty() ? null : periods.get(0);
        SchedulePeriodResponse last = periods.isEmpty() ? null : periods.get(periods.size() - 1);

        projectionRepository.save(LoanServicingProjection.fromContractSnapshot(
                loan.getId(),
                contract.getPrincipalAmount(),
                contract.getTotalInterest(),
                contract.getTotalFees(),
                contract.getTotalPenalties(),
                contract.getTotalRepayment(),
                first == null ? null : first.dueDate(),
                first == null ? null : first.totalDue(),
                last == null ? null : last.dueDate(),
                disbursedAt));
        return loan;
    }

    private static void requireSame(FinoraLoan loan, LoanContract contract, Long fineractLoanId) {
        if (!loan.getContractNumber().equals(contract.getContractNumber())
                || !loan.getFineractLoanId().equals(fineractLoanId)) {
            throw new IllegalStateException("Hồ sơ đã ánh xạ sang khoản vay servicing khác");
        }
    }

    private static String loanNumber(String applicationNumber) {
        if (applicationNumber.startsWith("LA-")) {
            return "LN-" + applicationNumber.substring(3);
        }
        return "LN-" + applicationNumber;
    }
}

