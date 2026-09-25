package com.finora.loan.service.application;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.repository.application.LoanApplicationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanTermsConfirmationExpiryStateService {

    private static final String SYSTEM_ACTOR = "LOAN_TERMS_EXPIRY_WORKER";

    private final LoanApplicationRepository applicationRepository;
    private final LoanContractProperties properties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return applicationRepository.findDueTermsConfirmationIds(
                clock.instant(), PageRequest.of(0, properties.expiryBatchSize()));
    }

    @Transactional
    public boolean expireOne(Long applicationId) {
        LoanApplication application = applicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", applicationId));
        Instant now = clock.instant();
        if (!application.expireTerms(SYSTEM_ACTOR, now)) {
            return false;
        }
        applicationRepository.saveAndFlush(application);
        return true;
    }
}
