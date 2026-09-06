package com.finora.loan.service.contract.impl;

import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.contract.LoanContractTerms;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.service.contract.ContractDocumentRenderer;
import com.finora.loan.service.contract.ContractNumberGenerator;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.support.HashingService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanContractCreationServiceImpl implements LoanContractCreationService {

    private final LoanContractRepository contractRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final ContractNumberGenerator numberGenerator;
    private final ContractDocumentRenderer documentRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties properties;

    /**
     * Dùng chung cho auto-approve và admin approve để hai luồng không tự dựng tài liệu khác nhau.
     * Unique application_id là chốt chặn cuối nếu hai worker cạnh tranh tạo cùng hợp đồng.
     */
    @Override
    @Transactional
    public LoanContract create(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            Instant expiresAt,
            ActorType actorType,
            String actorId,
            String reasonCode,
            Instant now
    ) {
        LoanContract existing = contractRepository.findByApplicationId(application.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        String contractNumber = numberGenerator.next();
        String documentContent = documentRenderer.render(
                contractNumber,
                application,
                finalSchedule,
                properties.termsVersion(),
                properties.documentVersion(),
                expiresAt
        );
        LoanContract contract = LoanContract.create(
                contractNumber,
                application.getId(),
                application.getBorrowerId(),
                terms(application, finalSchedule),
                properties.termsVersion(),
                properties.documentVersion(),
                documentContent,
                hashingService.sha256Text(documentContent),
                expiresAt,
                actorId,
                now
        );
        contractRepository.saveAndFlush(contract);
        historyRepository.saveAndFlush(LoanContractStatusHistory.create(
                contract.getId(), null, LoanContractStatus.PENDING_SIGNATURE, reasonCode,
                actorType, actorId, now, TraceContext.currentTraceIdOrCreate()
        ));
        return contract;
    }

    private LoanContractTerms terms(LoanApplication application, ScheduleCalculationSnapshot schedule) {
        return new LoanContractTerms(
                application.getRequestedAmount(),
                application.getRequestedTermMonths(),
                application.getFinalAnnualInterestRate(),
                application.getRepaymentMethodSnapshot(),
                schedule.getId(),
                schedule.getTotalInterest(),
                schedule.getTotalFees(),
                schedule.getTotalPenalties(),
                schedule.getTotalRepayment(),
                schedule.getFirstInstallment(),
                schedule.getMaximumInstallment(),
                schedule.getResponseHash(),
                schedule.getExpectedDisbursementDate()
        );
    }
}
