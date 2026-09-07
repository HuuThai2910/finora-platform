package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.ContractPdfArtifactType;
import com.finora.loan.domain.contract.LoanContractDocument;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanContractDocumentRepository extends JpaRepository<LoanContractDocument, Long> {

    Optional<LoanContractDocument> findByContractIdAndArtifactType(
            Long contractId,
            ContractPdfArtifactType artifactType
    );
}
