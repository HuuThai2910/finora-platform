package com.finora.loan.repository;

import com.finora.loan.domain.FineractProductMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FineractProductMappingRepository extends JpaRepository<FineractProductMapping, Long> {

    Optional<FineractProductMapping> findByLoanProductIdAndFinoraProductVersion(
            Long loanProductId,
            Long finoraProductVersion
    );
}
