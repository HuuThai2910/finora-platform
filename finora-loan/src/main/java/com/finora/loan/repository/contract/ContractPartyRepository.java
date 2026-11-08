package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.contract.enums.ContractPartyType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContractPartyRepository extends JpaRepository<ContractParty, Long> {

    List<ContractParty> findByContractIdOrderByPartyTypeAscCommitmentIdAsc(Long contractId);

    List<ContractParty> findByPartyTypeAndPartyIdOrderByCreatedAtDesc(
            ContractPartyType partyType,
            String partyId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select party from ContractParty party
            where party.contractId = :contractId and party.partyType = :partyType and party.partyId = :partyId
            """)
    List<ContractParty> findForUpdate(
            @Param("contractId") Long contractId,
            @Param("partyType") ContractPartyType partyType,
            @Param("partyId") String partyId
    );

    long countByContractIdAndPartyTypeAndStatusIn(
            Long contractId,
            ContractPartyType partyType,
            Collection<ContractPartyStatus> statuses
    );
}
