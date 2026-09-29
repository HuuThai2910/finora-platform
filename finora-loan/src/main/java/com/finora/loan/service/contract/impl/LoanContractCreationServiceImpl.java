package com.finora.loan.service.contract.impl;

import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.ActorType;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.contract.LoanContract;
import com.finora.loan.domain.contract.LoanContractDocument;
import com.finora.loan.domain.contract.LoanContractStatus;
import com.finora.loan.domain.contract.LoanContractStatusHistory;
import com.finora.loan.domain.contract.ContractParty;
import com.finora.loan.domain.contract.LoanContractTerms;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.messaging.event.LoanContractCreatedEventData;
import com.finora.loan.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.service.contract.ContractDocumentRenderer;
import com.finora.loan.service.contract.ContractPdfArtifact;
import com.finora.loan.service.contract.ContractPdfRenderer;
import com.finora.loan.service.contract.ContractNumberGenerator;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.service.contract.FundedContractAllocation;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.support.HashingService;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanContractCreationServiceImpl implements LoanContractCreationService {

    private final LoanContractRepository contractRepository;
    private final LoanContractDocumentRepository contractDocumentRepository;
    private final LoanContractStatusHistoryRepository historyRepository;
    private final ContractPartyRepository partyRepository;
    private final ContractNumberGenerator numberGenerator;
    private final ContractDocumentRenderer documentRenderer;
    private final ContractPdfRenderer pdfRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties properties;
    private final OutboxService outboxService;

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
        ContractPdfArtifact pdf = pdfRenderer.renderSignable(
                contractNumber, application, finalSchedule, properties.termsVersion(), expiresAt);
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
        contractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                contract.getId(), pdf.artifactType(), pdf.documentVersion(), pdf.contentHash(),
                pdf.content(), now
        ));
        historyRepository.saveAndFlush(LoanContractStatusHistory.create(
                contract.getId(), null, LoanContractStatus.PENDING_SIGNATURE, reasonCode,
                actorType, actorId, now, TraceContext.currentTraceIdOrCreate()
        ));
        outboxService.record(
                "LoanContract",
                contract.getContractNumber(),
                "LoanContractCreated",
                1,
                new LoanContractCreatedEventData(
                        contract.getContractNumber(),
                        contract.getApplicationId(),
                        contract.getDocumentHash(),
                        pdf.contentHash(),
                        contract.getTermsVersion(),
                        contract.getExpiresAt()
                )
        );
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

    @Override
    @Transactional
    public LoanContract createFunded(
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash,
            Instant expiresAt,
            String actorId,
            Instant now
    ) {
        LoanContract existing = contractRepository.findByApplicationId(application.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        validateAllocations(application, allocations, allocationVersion, allocationHash);
        String contractNumber = numberGenerator.next();
        String documentContent = documentRenderer.renderFunded(
                contractNumber, application, finalSchedule, properties.termsVersion(),
                properties.documentVersion(), expiresAt, allocations, allocationVersion, allocationHash);
        ContractPdfArtifact pdf = pdfRenderer.renderFundedSignable(
                contractNumber, application, finalSchedule, properties.termsVersion(),
                expiresAt, allocations, allocationVersion, allocationHash);
        LoanContract contract = LoanContract.create(
                contractNumber, application.getId(), application.getBorrowerId(),
                terms(application, finalSchedule), properties.termsVersion(), properties.documentVersion(),
                documentContent, hashingService.sha256Text(documentContent), expiresAt, actorId, now);
        contract.initializeMultiParty();
        contractRepository.saveAndFlush(contract);
        partyRepository.save(ContractParty.borrower(contract.getId(), application.getBorrowerId(), now));
        partyRepository.saveAll(allocations.stream()
                .map(allocation -> ContractParty.lender(
                        contract.getId(), allocation.commitmentId(), allocation.investorId(),
                        allocation.amount(), allocation.sharePercent(), allocation.paymentHoldReference(), now))
                .toList());
        partyRepository.flush();
        contractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                contract.getId(), pdf.artifactType(), pdf.documentVersion(), pdf.contentHash(), pdf.content(), now));
        historyRepository.saveAndFlush(LoanContractStatusHistory.create(
                contract.getId(), null, LoanContractStatus.PENDING_LENDER_SIGNATURES,
                "FULLY_FUNDED_CONTRACT_CREATED", ActorType.SYSTEM, actorId, now,
                TraceContext.currentTraceIdOrCreate()));
        outboxService.recordForPublication(
                "LoanContract", contract.getContractNumber(), "InvestorSignatureRequested", 1,
                new InvestorSignatureRequestedEventData(
                        application.getId(), application.getApplicationNumber(),
                        application.getInvestmentListingId(), contract.getContractNumber(),
                        contract.getDocumentHash(), pdf.contentHash(), allocationVersion, allocationHash,
                        (int) allocations.stream().map(FundedContractAllocation::investorId).distinct().count(),
                        expiresAt));
        return contract;
    }

    private void validateAllocations(
            LoanApplication application,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash
    ) {
        if (allocations == null || allocations.isEmpty() || allocationVersion < 1
                || allocationHash == null || !allocationHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Allocation snapshot không hợp lệ");
        }
        HashSet<Long> commitmentIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (FundedContractAllocation allocation : allocations) {
            if (allocation == null || allocation.commitmentId() == null
                    || allocation.investorId() == null || allocation.investorId().isBlank()
                    || allocation.amount() == null || allocation.amount().signum() <= 0
                    || allocation.sharePercent() == null || allocation.sharePercent().signum() <= 0
                    || allocation.paymentHoldReference() == null || allocation.paymentHoldReference().isBlank()
                    || !commitmentIds.add(allocation.commitmentId())) {
                throw new IllegalArgumentException("Allocation lender bị thiếu hoặc trùng");
            }
            total = total.add(allocation.amount());
        }
        if (total.setScale(2, java.math.RoundingMode.HALF_UP)
                .compareTo(application.getRequestedAmount()) != 0) {
            throw new IllegalArgumentException("Tổng allocation không khớp số tiền vay");
        }
    }
}
