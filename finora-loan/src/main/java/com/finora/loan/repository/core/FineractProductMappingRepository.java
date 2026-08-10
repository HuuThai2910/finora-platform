package com.finora.loan.repository.core;

import com.finora.loan.domain.core.FineractProductMapping;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FineractProductMappingRepository extends JpaRepository<FineractProductMapping, Long> {

    Optional<FineractProductMapping> findByLoanProductIdAndFinoraProductVersion(
            Long loanProductId,
            Long finoraProductVersion
    );
}
