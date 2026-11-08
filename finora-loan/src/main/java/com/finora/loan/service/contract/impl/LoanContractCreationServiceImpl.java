package com.finora.loan.service.contract.impl;

import com.finora.common.logging.TraceContext;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.config.PadesSignatureProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.entity.ContractPdfSignature;
import com.finora.loan.domain.contract.entity.LoanContractStatusHistory;
import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.ContractPdfArtifactType;
import com.finora.loan.domain.contract.enums.ContractPdfSignatureStatus;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.model.LoanContractTerms;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.signature.client.SignatureProvider;
import com.finora.loan.messaging.event.InvestorSignatureRequestedEventData;
import com.finora.loan.messaging.event.LoanContractCreatedEventData;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.ContractPdfSignatureRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.service.contract.ContractDocumentRenderer;
import com.finora.loan.service.contract.ContractNumberGenerator;
import com.finora.loan.service.contract.ContractPdfArtifact;
import com.finora.loan.service.contract.ContractPdfRenderer;
import com.finora.loan.service.contract.ContractPdfSignatureService;
import com.finora.loan.service.contract.FundedContractAllocation;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.service.outbox.OutboxService;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lập hợp đồng vay từ hồ sơ đã duyệt và lịch trả nợ cuối, gồm văn bản click-wrap và PDF để ký.
 *
 * <p>Hợp đồng, PDF, history và outbox được ghi trong cùng transaction.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanContractCreationServiceImpl implements LoanContractCreationService {

    private final LoanContractRepository loanContractRepository;
    private final LoanContractDocumentRepository loanContractDocumentRepository;
    private final LoanContractStatusHistoryRepository loanContractStatusHistoryRepository;
    private final ContractPartyRepository contractPartyRepository;
    private final ContractNumberGenerator contractNumberGenerator;
    private final ContractDocumentRenderer contractDocumentRenderer;
    private final ContractPdfRenderer contractPdfRenderer;
    private final HashingService hashingService;
    private final LoanContractProperties loanContractProperties;
    private final PadesSignatureProperties padesSignatureProperties;
    private final OutboxService outboxService;
    private final ContractPdfSignatureRepository contractPdfSignatureRepository;
    private final ContractPdfSignatureService contractPdfSignatureService;
    private final SignatureProvider signatureProvider;

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
        LoanContract existing = loanContractRepository.findByApplicationId(application.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        String contractNumber = contractNumberGenerator.next();
        // Văn bản theo cách tính lãi của sản phẩm core trên hồ sơ (Q3): V4 nhận điều 4 tính lãi theo ngày.
        ContractTextVersion textVersion = ContractTextVersion.of(application, loanContractProperties);
        String documentContent = contractDocumentRenderer.render(
                contractNumber,
                application,
                finalSchedule,
                textVersion.termsVersion(),
                textVersion.documentVersion(),
                expiresAt
        );
        ContractPdfArtifact pdf = contractPdfRenderer.renderSignable(
                contractNumber, application, finalSchedule, textVersion.termsVersion(), expiresAt);
        LoanContract contract = LoanContract.create(
                contractNumber,
                application.getId(),
                application.getBorrowerId(),
                terms(application, finalSchedule),
                textVersion.termsVersion(),
                textVersion.documentVersion(),
                documentContent,
                hashingService.sha256Text(documentContent),
                expiresAt,
                actorId,
                now
        );
        // Văn bản vừa render in điều 4 với biểu phí này; Payment tính phí trả trước theo đúng giá trị lưu ở đây.
        contract.recordEarlySettlementPolicy(LoanServicingContractTerms.EARLY_SETTLEMENT_POLICY_VERSION);
        loanContractRepository.saveAndFlush(contract);
        loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                contract.getId(), pdf.artifactType(), pdf.documentVersion(), pdf.contentHash(),
                pdf.content(), now
        ));
        loanContractStatusHistoryRepository.saveAndFlush(LoanContractStatusHistory.create(
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
        LoanContract existing = loanContractRepository.findByApplicationId(application.getId()).orElse(null);
        if (existing != null) {
            // Một hồ sơ chỉ có một Contract (uq application_id). Đủ vốn lại sau khi nhà đầu tư rút thì phát hành
            // lại chính Contract đó thay vì lập hợp đồng mới.
            return existing.isPendingRefunding()
                    ? reissueFunded(existing, application, finalSchedule, allocations, allocationVersion,
                            allocationHash, expiresAt, actorId, now)
                    : existing;
        }
        validateAllocations(application, allocations, allocationVersion, allocationHash);
        String contractNumber = contractNumberGenerator.next();
        ContractTextVersion textVersion = ContractTextVersion.of(application, loanContractProperties);
        // R5/D13: cách ký chốt lúc lập Contract; tắt PAdES chỉ ảnh hưởng hợp đồng mới, không đổi hợp đồng đang ký.
        boolean pades = padesSignatureProperties.isEnabled();
        String documentContent = contractDocumentRenderer.renderFunded(
                contractNumber, application, finalSchedule, textVersion.termsVersion(),
                textVersion.documentVersion(), expiresAt, allocations, allocationVersion, allocationHash);
        ContractPdfArtifact pdf = contractPdfRenderer.renderFundedSignable(
                contractNumber, application, finalSchedule, textVersion.termsVersion(),
                expiresAt, allocations, allocationVersion, allocationHash, pades);
        LoanContract contract = LoanContract.create(
                contractNumber, application.getId(), application.getBorrowerId(),
                terms(application, finalSchedule), textVersion.termsVersion(), textVersion.documentVersion(),
                documentContent, hashingService.sha256Text(documentContent), expiresAt, actorId, now);
        contract.recordEarlySettlementPolicy(LoanServicingContractTerms.EARLY_SETTLEMENT_POLICY_VERSION);
        contract.initializeMultiParty();
        if (pades) {
            contract.usePadesSignatures();
        }
        loanContractRepository.saveAndFlush(contract);
        contractPartyRepository.save(ContractParty.borrower(contract.getId(), application.getBorrowerId(), now));
        contractPartyRepository.saveAll(allocations.stream()
                .map(allocation -> ContractParty.lender(
                        contract.getId(), allocation.commitmentId(), allocation.investorId(),
                        allocation.amount(), allocation.sharePercent(), allocation.paymentHoldReference(), now))
                .toList());
        contractPartyRepository.flush();
        loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                contract.getId(), pdf.artifactType(), pdf.documentVersion(), pdf.contentHash(), pdf.content(), now));
        loanContractStatusHistoryRepository.saveAndFlush(LoanContractStatusHistory.create(
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

    /**
     * FUNDING-REOPEN-001: phát hành lại văn bản theo allocation mới sau khi gọi vốn bổ sung.
     *
     * <p>Phần vốn còn trong hợp đồng phải có mặt nguyên vẹn trong allocation mới (Investment không cho hủy phần vốn đã
     * gắn hợp đồng); phần vốn mới thành party chờ ký. Bên đã ký giữ chấp thuận: với PAdES, chữ ký cũ chuyển SUPERSEDED
     * (vẫn lưu làm bằng chứng) và được nhúng lại lên bản mới bằng khóa của chính bên đó khi provider ký cục bộ (MOCK);
     * provider cần nhà đầu tư xác nhận (SmartCA) thì bên đó phải ký lại.</p>
     */
    private LoanContract reissueFunded(
            LoanContract existing,
            LoanApplication application,
            ScheduleCalculationSnapshot finalSchedule,
            List<FundedContractAllocation> allocations,
            long allocationVersion,
            String allocationHash,
            Instant expiresAt,
            String actorId,
            Instant now
    ) {
        LoanContract contract = loanContractRepository.findByIdForUpdate(existing.getId()).orElseThrow();
        if (!contract.isPendingRefunding()) {
            return contract;
        }
        validateAllocations(application, allocations, allocationVersion, allocationHash);
        List<ContractParty> parties = contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(
                contract.getId());
        Map<Long, ContractParty> retained = parties.stream()
                .filter(ContractParty::isActiveLender)
                .collect(Collectors.toMap(ContractParty::getCommitmentId, Function.identity()));
        HashSet<Long> released = parties.stream()
                .filter(party -> party.getPartyType() == ContractPartyType.LENDER && !party.isActiveLender())
                .map(ContractParty::getCommitmentId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, FundedContractAllocation> byCommitment = allocations.stream()
                .collect(Collectors.toMap(FundedContractAllocation::commitmentId, Function.identity()));
        for (ContractParty party : retained.values()) {
            FundedContractAllocation allocation = byCommitment.get(party.getCommitmentId());
            if (allocation == null || !allocation.investorId().equals(party.getPartyId())
                    || allocation.amount().compareTo(party.getAllocationAmount()) != 0) {
                throw LoanBusinessException.conflict(
                        "REFUNDED_ALLOCATION_MISMATCH",
                        "Allocation gọi vốn bổ sung không giữ nguyên phần vốn còn trong hợp đồng");
            }
        }
        if (allocations.stream().anyMatch(allocation -> released.contains(allocation.commitmentId()))) {
            throw LoanBusinessException.conflict(
                    "REFUNDED_ALLOCATION_MISMATCH", "Allocation gọi vốn bổ sung chứa phần vốn đã rời hợp đồng");
        }

        String documentContent = contractDocumentRenderer.renderFunded(
                contract.getContractNumber(), application, finalSchedule, contract.getTermsVersion(),
                contract.getDocumentVersion(), expiresAt, allocations, allocationVersion, allocationHash);
        ContractPdfArtifact pdf = contractPdfRenderer.renderFundedSignable(
                contract.getContractNumber(), application, finalSchedule, contract.getTermsVersion(),
                expiresAt, allocations, allocationVersion, allocationHash, contract.isPadesSigned());
        contract.reissueAfterRefunding(documentContent, hashingService.sha256Text(documentContent), expiresAt,
                actorId, now);
        loanContractRepository.saveAndFlush(contract);

        // UNIQUE(contract_id, artifact_type): bản SIGNABLE cũ được thay; bytes đã có chữ ký vẫn nằm trong các dòng
        // loan_contract_signatures SUPERSEDED bên dưới.
        loanContractDocumentRepository.findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .ifPresent(loanContractDocumentRepository::delete);
        loanContractDocumentRepository.flush();
        loanContractDocumentRepository.saveAndFlush(LoanContractDocument.create(
                contract.getId(), pdf.artifactType(), pdf.documentVersion(), pdf.contentHash(), pdf.content(), now));

        contractPartyRepository.saveAll(allocations.stream()
                .filter(allocation -> !retained.containsKey(allocation.commitmentId()))
                .map(allocation -> ContractParty.lender(
                        contract.getId(), allocation.commitmentId(), allocation.investorId(),
                        allocation.amount(), allocation.sharePercent(), allocation.paymentHoldReference(), now))
                .toList());
        contractPartyRepository.flush();
        if (contract.isPadesSigned()) {
            carryOverPadesSignatures(contract, retained.values().stream()
                    .filter(party -> party.getStatus() == ContractPartyStatus.SIGNED)
                    .toList(), now);
        }

        loanContractStatusHistoryRepository.saveAndFlush(LoanContractStatusHistory.create(
                contract.getId(), LoanContractStatus.PENDING_REFUNDING, LoanContractStatus.PENDING_LENDER_SIGNATURES,
                "REFUNDED_CONTRACT_REISSUED", ActorType.SYSTEM, actorId, now,
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

    private void carryOverPadesSignatures(LoanContract contract, List<ContractParty> signedParties, Instant now) {
        // Chuỗi PAdES mới bắt đầu từ bản SIGNABLE vừa phát hành: chữ ký cũ rời chuỗi trước khi nhúng lại.
        contractPdfSignatureRepository.findByContractIdAndStatusOrderBySequenceNumberAsc(
                        contract.getId(), ContractPdfSignatureStatus.SIGNED)
                .forEach(signature -> signature.supersede(now));
        contractPdfSignatureRepository.flush();
        Map<String, List<ContractParty>> byInvestor = signedParties.stream()
                .sorted(Comparator.comparing(ContractParty::getSignedAt).thenComparing(ContractParty::getCommitmentId))
                .collect(Collectors.groupingBy(ContractParty::getPartyId, LinkedHashMap::new, Collectors.toList()));
        if (!signatureProvider.completesSynchronously()) {
            byInvestor.values().forEach(investorParties -> investorParties.forEach(party -> party.resetForReissue(now)));
            contractPartyRepository.saveAllAndFlush(signedParties);
            return;
        }
        for (Map.Entry<String, List<ContractParty>> entry : byInvestor.entrySet()) {
            ContractParty first = entry.getValue().getFirst();
            ContractPdfSignature signature = contractPdfSignatureService.signSynchronously(contract,
                    new ContractPdfSignatureService.PadesSignerRequest(
                            ContractPartyType.LENDER, entry.getKey(), signatureProvider.signingCertificate(entry.getKey()),
                            signatureProvider.type(), signatureProvider.method(),
                            Objects.requireNonNull(first.getIdempotencyKey(), "idempotencyKey"),
                            Objects.requireNonNull(first.getRequestHash(), "requestHash")),
                    now);
            entry.getValue().forEach(party -> party.carryOverSignature(
                    signature.getProviderTransactionId(), signature.getEvidenceHash(), now));
        }
        contractPartyRepository.saveAllAndFlush(signedParties);
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
