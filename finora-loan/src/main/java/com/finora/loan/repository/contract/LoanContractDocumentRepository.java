package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContractDocument;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanContractDocumentRepository extends JpaRepository<LoanContractDocument, Long> {

    Optional<LoanContractDocument> findByContractIdAndArtifactType(
            Long contractId,
            ContractPdfArtifactType artifactType
    );

    /** Chỉ đọc hash để dựng response, không kéo bytes PDF (BYTEA) của artifact. */
    @Query("""
            select document.contentHash from LoanContractDocument document
            where document.contractId = :contractId and document.artifactType = :artifactType
            """)
    Optional<String> findContentHash(
            @Param("contractId") Long contractId,
            @Param("artifactType") ContractPdfArtifactType artifactType
    );
}
