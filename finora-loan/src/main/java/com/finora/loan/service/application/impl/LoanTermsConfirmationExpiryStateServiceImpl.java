package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.service.application.LoanTermsConfirmationExpiryStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Các bước transaction của worker hết hạn xác nhận điều khoản: lấy batch hồ sơ đến hạn và chuyển từng hồ sơ sang hết
 * hạn.
 *
 * <p>Mỗi hồ sơ được khóa và commit riêng nên một hồ sơ lỗi không làm hỏng cả batch.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanTermsConfirmationExpiryStateServiceImpl implements LoanTermsConfirmationExpiryStateService {

    private static final String SYSTEM_ACTOR = "LOAN_TERMS_EXPIRY_WORKER";

    private final LoanApplicationRepository loanApplicationRepository;
    private final LoanContractProperties loanContractProperties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return loanApplicationRepository.findDueTermsConfirmationIds(
                clock.instant(), PageRequest.of(0, loanContractProperties.expiryBatchSize()));
    }

    @Override
    @Transactional
    public boolean expireOne(Long applicationId) {
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "id", applicationId));
        Instant now = clock.instant();
        if (!application.expireTerms(SYSTEM_ACTOR, now)) {
            return false;
        }
        loanApplicationRepository.saveAndFlush(application);
        return true;
    }
}
