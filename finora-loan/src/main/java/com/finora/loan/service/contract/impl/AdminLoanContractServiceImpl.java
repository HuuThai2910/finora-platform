package com.finora.loan.service.contract.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractDocument;
import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import com.finora.loan.domain.contract.enums.ContractPdfArtifactType;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import com.finora.loan.domain.contract.enums.SignatureProviderType;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.dto.contract.response.AdminContractPartyResponse;
import com.finora.loan.dto.contract.response.AdminContractSigningPolicyResponse;
import com.finora.loan.dto.contract.response.AdminLoanContractResponse;
import com.finora.loan.integration.signature.client.SignatureProvider;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractDocumentRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.service.contract.AdminLoanContractService;
import com.finora.loan.service.contract.LoanContractPdfContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Đọc hợp đồng cho màn quản trị.
 *
 * <p>Người vay ký trên chính bản ghi hợp đồng ({@code signedAt}, {@code declinedAt}, {@code signatureProvider}); dòng
 * bên ký BORROWER chỉ ghi người vay có mặt trong hợp đồng nhiều bên và không đổi trạng thái khi ký. Vì vậy dòng người vay
 * trả ra được dựng từ hợp đồng, dòng nhà đầu tư lấy từ {@code contract_parties}.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminLoanContractServiceImpl implements AdminLoanContractService {

    private static final List<LoanContractStatus> SIGNED_STATUSES =
            List.of(LoanContractStatus.SIGNED, LoanContractStatus.EFFECTIVE, LoanContractStatus.COMPLETED);

    private final LoanContractRepository loanContractRepository;
    private final ContractPartyRepository contractPartyRepository;
    private final LoanContractDocumentRepository loanContractDocumentRepository;
    private final LoanContractStatusHistoryRepository loanContractStatusHistoryRepository;
    private final FinoraLoanRepository finoraLoanRepository;
    private final SignatureProvider signatureProvider;
    private final LoanContractProperties loanContractProperties;

    @Override
    @Transactional(readOnly = true)
    public AdminLoanContractResponse detail(String contractNumber) {
        LoanContract contract = contract(contractNumber);
        List<ContractParty> lenders = contractPartyRepository
                .findByContractIdOrderByPartyTypeAscCommitmentIdAsc(contract.getId()).stream()
                .filter(party -> party.getPartyType() == ContractPartyType.LENDER)
                .sorted(Comparator.comparing(ContractParty::getAllocationAmount,
                                Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                        .thenComparing(ContractParty::getCommitmentId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<AdminContractPartyResponse> parties = Stream.concat(
                lenders.stream().map(AdminLoanContractServiceImpl::lender),
                Stream.of(borrower(contract))).toList();
        LoanContractDocument pdf = currentPdf(contract);
        String loanNumber = finoraLoanRepository.findByLoanApplicationId(contract.getApplicationId())
                .map(FinoraLoan::getLoanNumber).orElse(null);
        Instant issuedAt = loanContractStatusHistoryRepository
                .findIssueTimes(contract.getId(), LoanContractStatus.PENDING_REFUNDING,
                        LoanContractStatus.PENDING_LENDER_SIGNATURES).stream()
                .findFirst().orElse(contract.getCreatedAt());
        return new AdminLoanContractResponse(
                contract.getContractNumber(),
                contract.getContractCode(),
                loanNumber,
                contract.getStatus(),
                contract.isMultiParty(),
                contract.getPrincipalAmount(),
                contract.getTermMonths(),
                contract.getAnnualInterestRate(),
                contract.getDocumentHash(),
                pdf == null ? null : pdf.getContentHash(),
                signatureProviderOf(contract, lenders),
                issuedAt,
                contract.getExpiresAt(),
                contract.getEffectiveAt(),
                contract.getDeclinedAt(),
                contract.getDeclineReasonCode(),
                parties);
    }

    @Override
    @Transactional(readOnly = true)
    public LoanContractPdfContent document(String contractNumber) {
        LoanContract contract = contract(contractNumber);
        LoanContractDocument document = currentPdf(contract);
        if (document == null) {
            throw new ResourceNotFoundException("Loan Contract PDF", "contractNumber", contractNumber);
        }
        // Tên tệp theo mã hiển thị HĐ-... để người tải không thấy mã kỹ thuật; hợp đồng cũ chưa có mã thì dùng số hợp đồng.
        String fileName = (contract.getContractCode() == null ? contractNumber : contract.getContractCode()) + ".pdf";
        return new LoanContractPdfContent(fileName, document.getContentType(), document.getContentHash(),
                document.contentCopy());
    }

    @Override
    public AdminContractSigningPolicyResponse signingPolicy() {
        return new AdminContractSigningPolicyResponse(
                signatureProvider.type(), loanContractProperties.signatureWindow().toHours());
    }

    private LoanContract contract(String contractNumber) {
        return loanContractRepository.findByContractNumber(contractNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Loan Contract", "contractNumber", contractNumber));
    }

    /**
     * Nhà cung cấp đã dùng để ký (người vay trước, rồi tới nhà đầu tư đầu tiên đã ký); chưa ai ký thì là cấu hình hiện
     * hành, vì bên ký sau sẽ ký bằng nhà cung cấp đó.
     */
    private SignatureProviderType signatureProviderOf(LoanContract contract, List<ContractParty> lenders) {
        if (contract.getSignatureProvider() != null) {
            return contract.getSignatureProvider();
        }
        return lenders.stream().map(ContractParty::getSignatureProvider).filter(Objects::nonNull).findFirst()
                .orElse(signatureProvider.type());
    }

    private static AdminContractPartyResponse lender(ContractParty party) {
        return new AdminContractPartyResponse(
                ContractPartyType.LENDER,
                party.getPartyId(),
                party.getCommitmentId(),
                party.getAllocationAmount(),
                party.getAllocationSharePercent(),
                party.getStatus(),
                party.getSignatureProvider(),
                party.getSignedAt(),
                party.getClosedAt());
    }

    private static AdminContractPartyResponse borrower(LoanContract contract) {
        ContractPartyStatus status;
        if (contract.getSignedAt() != null) {
            status = ContractPartyStatus.SIGNED;
        } else if (contract.getDeclinedAt() != null && contract.getBorrowerId().equals(contract.getDeclinedBy())) {
            status = ContractPartyStatus.DECLINED;
        } else if (contract.getStatus() == LoanContractStatus.EXPIRED) {
            status = ContractPartyStatus.EXPIRED;
        } else if (contract.getStatus() == LoanContractStatus.SIGNING) {
            status = ContractPartyStatus.SIGNING;
        } else {
            status = ContractPartyStatus.PENDING_SIGNATURE;
        }
        return new AdminContractPartyResponse(
                ContractPartyType.BORROWER,
                contract.getBorrowerId(),
                null,
                contract.getPrincipalAmount(),
                null,
                status,
                contract.getSignatureProvider(),
                contract.getSignedAt(),
                status == ContractPartyStatus.DECLINED ? contract.getDeclinedAt() : null);
    }

    /** Cùng quy tắc với {@code /document} của người vay và nhà đầu tư: bản đủ chữ ký sau khi ký xong, trước đó bản để ký. */
    private LoanContractDocument currentPdf(LoanContract contract) {
        if (SIGNED_STATUSES.contains(contract.getStatus())) {
            for (ContractPdfArtifactType type
                    : List.of(ContractPdfArtifactType.SIGNED_PDF, ContractPdfArtifactType.SIGNED_RECEIPT)) {
                LoanContractDocument document = loanContractDocumentRepository
                        .findByContractIdAndArtifactType(contract.getId(), type).orElse(null);
                if (document != null) {
                    return document;
                }
            }
        }
        return loanContractDocumentRepository
                .findByContractIdAndArtifactType(contract.getId(), ContractPdfArtifactType.SIGNABLE)
                .orElse(null);
    }
}
