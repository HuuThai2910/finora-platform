package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.domain.contract.ContractPdfSignature;
import com.finora.loan.domain.contract.ContractPdfSignatureStatus;
import com.finora.loan.domain.contract.ContractPdfSignatureSummary;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Chuỗi chữ ký PAdES của Contract. Mọi thao tác ghi chạy sau khi đã khóa dòng Contract, nên các truy vấn ở đây không
 * tự khóa; partial unique index trong V32 là lớp chặn cuối nếu có code đi vòng qua khóa đó.
 */
public interface ContractPdfSignatureRepository extends JpaRepository<ContractPdfSignature, Long> {

    /** Phiên ký đang chờ (tối đa một dòng nhờ {@code uq_loan_contract_signatures_in_flight}). */
    Optional<ContractPdfSignature> findFirstByContractIdAndStatus(Long contractId, ContractPdfSignatureStatus status);

    /** Bản đầu chuỗi: bản SIGNED có số thứ tự lớn nhất ({@code uq_loan_contract_signatures_sequence}). */
    Optional<ContractPdfSignature> findFirstByContractIdAndStatusOrderBySequenceNumberDesc(
            Long contractId,
            ContractPdfSignatureStatus status
    );

    Optional<ContractPdfSignature> findFirstByContractIdAndSignerTypeAndSignerIdAndStatus(
            Long contractId,
            ContractPartyType signerType,
            String signerId,
            ContractPdfSignatureStatus status
    );

    @Query("""
            select coalesce(max(signature.attemptNumber), 0) from ContractPdfSignature signature
            where signature.contractId = :contractId and signature.signerType = :signerType
              and signature.signerId = :signerId
            """)
    int maxAttemptNumber(
            @Param("contractId") Long contractId,
            @Param("signerType") ContractPartyType signerType,
            @Param("signerId") String signerId
    );

    long countByContractIdAndStatus(Long contractId, ContractPdfSignatureStatus status);

    @Query("""
            select new com.finora.loan.domain.contract.ContractPdfSignatureSummary(
                signature.id, signature.signerType, signature.signerId, signature.sequenceNumber,
                signature.provider, signature.method, signature.contentHash, signature.contentLength,
                signature.certificateSerial, signature.certificateSha256, signature.cmsHash, signature.evidenceHash,
                signature.signingTime, signature.signedAt)
            from ContractPdfSignature signature
            where signature.contractId = :contractId and signature.status = :status
            order by signature.sequenceNumber asc
            """)
    List<ContractPdfSignatureSummary> findSummaries(
            @Param("contractId") Long contractId,
            @Param("status") ContractPdfSignatureStatus status
    );

    /** Worker lấy các phiên đang chờ cũ trước theo {@code idx_loan_contract_signatures_in_flight_due}. */
    @Query("""
            select new com.finora.loan.repository.contract.InFlightPdfSignature(
                signature.id, contract.contractNumber, signature.signerType, signature.signerId,
                signature.preparedAt)
            from ContractPdfSignature signature, LoanContract contract
            where contract.id = signature.contractId and signature.status = :status
            order by signature.preparedAt asc, signature.id asc
            """)
    List<InFlightPdfSignature> findInFlight(
            @Param("status") ContractPdfSignatureStatus status,
            Pageable pageable
    );
}
