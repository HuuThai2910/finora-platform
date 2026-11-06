package com.finora.loan.service.servicing.impl;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.dto.core.response.SchedulePeriodResponse;
import com.finora.loan.mapper.core.ScheduleCalculationSnapshotMapper;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.servicing.ServicingActivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Tạo khoản vay servicing trong cùng transaction hoàn tất Disbursement Saga.
 */
@Service
@RequiredArgsConstructor
public class ServicingActivationServiceImpl implements ServicingActivationService {

    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanServicingProjectionRepository loanServicingProjectionRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final ScheduleCalculationSnapshotMapper scheduleCalculationSnapshotMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FinoraLoan activate(
            LoanApplication application,
            LoanContract contract,
            Long fineractLoanId,
            Instant disbursedAt
    ) {
        FinoraLoan existing = finoraLoanRepository.findByLoanApplicationId(application.getId()).orElse(null);
        if (existing != null) {
            requireSame(existing, contract, fineractLoanId);
            return existing;
        }

        FinoraLoan loan = finoraLoanRepository.saveAndFlush(FinoraLoan.activate(
                loanNumber(application.getApplicationNumber()),
                application.getId(),
                application.getApplicationNumber(),
                contract.getContractNumber(),
                application.getBorrowerId(),
                fineractLoanId,
                contract.getPrincipalAmount(),
                "VND",
                disbursedAt));

        ScheduleCalculationSnapshot snapshot = scheduleCalculationSnapshotRepository
                .findById(contract.getCalculationSnapshotId())
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy lịch Fineract đã dùng để lập hợp đồng"));
        List<SchedulePeriodResponse> periods = scheduleCalculationSnapshotMapper.periods(snapshot).stream()
                .filter(period -> period.period() != null && period.period() > 0)
                .sorted(Comparator.comparing(SchedulePeriodResponse::period))
                .toList();
        SchedulePeriodResponse firstPeriod = periods.isEmpty() ? null : periods.get(0);
        SchedulePeriodResponse lastPeriod = periods.isEmpty() ? null : periods.get(periods.size() - 1);

        loanServicingProjectionRepository.save(LoanServicingProjection.fromContractSnapshot(
                loan.getId(),
                contract.getPrincipalAmount(),
                contract.getTotalInterest(),
                contract.getTotalFees(),
                contract.getTotalPenalties(),
                contract.getTotalRepayment(),
                firstPeriod == null ? null : firstPeriod.dueDate(),
                firstPeriod == null ? null : firstPeriod.totalDue(),
                lastPeriod == null ? null : lastPeriod.dueDate(),
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
