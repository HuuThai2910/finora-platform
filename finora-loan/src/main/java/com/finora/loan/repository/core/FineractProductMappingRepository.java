package com.finora.loan.repository.core;

import com.finora.loan.domain.core.entity.FineractProductMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FineractProductMappingRepository extends JpaRepository<FineractProductMapping, Long> {

    Optional<FineractProductMapping> findByLoanProductIdAndFinoraProductVersion(
            Long loanProductId,
            Long finoraProductVersion
    );
}
