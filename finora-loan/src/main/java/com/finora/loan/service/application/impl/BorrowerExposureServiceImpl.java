package com.finora.loan.service.application.impl;

import com.finora.loan.config.BorrowerExposureProperties;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanFundingStatus;
import com.finora.loan.domain.application.enums.TermsConfirmationStatus;
import com.finora.loan.domain.servicing.enums.FinoraLoanStatus;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.service.application.BorrowerExposureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Kiểm hạn mức dư nợ khi nộp hồ sơ (D5, {@code LEGAL-LIMIT-01} — policy demo, chưa phải hằng số pháp lý).
 *
 * <p>Concurrency: hai hồ sơ của cùng người vay nộp đồng thời đều có thể thấy tổng cũ. Advisory lock theo borrower trong
 * transaction ghi hồ sơ tuần tự hóa hai lần kiểm; lần sau chờ lần trước commit rồi mới đọc tổng, nên thấy hồ sơ vừa ghi.</p>
 */
@Service
@RequiredArgsConstructor
public class BorrowerExposureServiceImpl implements BorrowerExposureService {

    static final List<LoanApplicationStatus> OPEN_APPLICATION_STATUSES = List.of(
            LoanApplicationStatus.SUBMITTED,
            LoanApplicationStatus.ELIGIBILITY_PENDING,
            LoanApplicationStatus.SCORING,
            LoanApplicationStatus.SCORING_RETRY_PENDING,
            LoanApplicationStatus.PENDING_REVIEW,
            LoanApplicationStatus.APPROVED);
    static final List<LoanFundingStatus> CLOSED_FUNDING_STATUSES = List.of(
            LoanFundingStatus.EXPIRED, LoanFundingStatus.CANCELLED);
    static final List<TermsConfirmationStatus> CLOSED_TERMS_STATUSES = List.of(
            TermsConfirmationStatus.DECLINED, TermsConfirmationStatus.EXPIRED);
    static final List<FinoraLoanStatus> OUTSTANDING_LOAN_STATUSES = List.of(
            FinoraLoanStatus.ACTIVE, FinoraLoanStatus.RESTRUCTURING, FinoraLoanStatus.DEFAULTED,
            FinoraLoanStatus.WRITTEN_OFF);

    private final BorrowerExposureProperties borrowerExposureProperties;
    private final LoanApplicationRepository loanApplicationRepository;
    private final FinoraLoanRepository finoraLoanRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWithinLimit(String borrowerId, BigDecimal requestedAmount) {
        if (!borrowerExposureProperties.enabled()) return;
        loanApplicationRepository.lockBorrowerExposure("finora-loan-exposure:" + borrowerId);
        BigDecimal outstanding = zeroIfNull(
                finoraLoanRepository.sumOutstandingPrincipal(borrowerId, OUTSTANDING_LOAN_STATUSES));
        BigDecimal pending = zeroIfNull(loanApplicationRepository.sumOpenRequestedAmount(
                borrowerId, OPEN_APPLICATION_STATUSES, CLOSED_FUNDING_STATUSES, CLOSED_TERMS_STATUSES));
        BigDecimal limit = borrowerExposureProperties.maxOutstanding();
        if (outstanding.add(pending).add(requestedAmount).compareTo(limit) > 0) {
            BigDecimal remaining = limit.subtract(outstanding).subtract(pending).max(BigDecimal.ZERO);
            // App hiển thị nguyên thông điệp này cho người vay, nên số tiền viết theo kiểu Việt Nam (100.000.000).
            throw new LoanBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BORROWER_EXPOSURE_LIMIT_EXCEEDED",
                    "Tổng dư nợ và hồ sơ đang xử lý vượt hạn mức " + formatDong(limit)
                            + " đồng; số tiền còn có thể đề nghị: " + formatDong(remaining) + " đồng");
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String formatDong(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,d", amount.setScale(0, RoundingMode.DOWN).longValueExact()).replace(',', '.');
    }
}
