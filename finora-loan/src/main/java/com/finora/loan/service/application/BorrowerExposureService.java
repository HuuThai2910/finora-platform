package com.finora.loan.service.application;

import com.finora.loan.config.BorrowerExposureProperties;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanFundingStatus;
import com.finora.loan.domain.application.TermsConfirmationStatus;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm hạn mức dư nợ khi nộp hồ sơ (D5, {@code LEGAL-LIMIT-01} — policy demo, chưa phải hằng số pháp lý).
 *
 * <p>Dư nợ tính gồm: gốc còn lại của các khoản đang phục vụ, cộng số tiền đề nghị của các hồ sơ còn có thể thành khoản
 * vay (chưa bị từ chối/rút, chưa hết hạn hay hủy gọi vốn, chưa thành khoản vay), cộng hồ sơ mới. Chỉ xét số tiền hồ sơ
 * mới thì người vay có thể nộp nhiều hồ sơ song song để vượt hạn mức.</p>
 *
 * <p>Concurrency: hai hồ sơ của cùng người vay nộp đồng thời đều có thể thấy tổng cũ. Advisory lock theo borrower trong
 * transaction ghi hồ sơ tuần tự hóa hai lần kiểm; lần sau chờ lần trước commit rồi mới đọc tổng, nên thấy hồ sơ vừa ghi.</p>
 */
@Service
@RequiredArgsConstructor
public class BorrowerExposureService {

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

    private final BorrowerExposureProperties properties;
    private final LoanApplicationRepository applications;
    private final FinoraLoanRepository loans;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireWithinLimit(String borrowerId, BigDecimal requestedAmount) {
        if (!properties.enabled()) return;
        applications.lockBorrowerExposure("finora-loan-exposure:" + borrowerId);
        BigDecimal outstanding = nonNull(loans.sumOutstandingPrincipal(borrowerId, OUTSTANDING_LOAN_STATUSES));
        BigDecimal pending = nonNull(applications.sumOpenRequestedAmount(borrowerId, OPEN_APPLICATION_STATUSES,
                CLOSED_FUNDING_STATUSES, CLOSED_TERMS_STATUSES));
        BigDecimal limit = properties.maxOutstanding();
        if (outstanding.add(pending).add(requestedAmount).compareTo(limit) > 0) {
            BigDecimal remaining = limit.subtract(outstanding).subtract(pending).max(BigDecimal.ZERO);
            // App hiển thị nguyên thông điệp này cho người vay, nên số tiền viết theo kiểu Việt Nam (100.000.000).
            throw new LoanBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "BORROWER_EXPOSURE_LIMIT_EXCEEDED",
                    "Tổng dư nợ và hồ sơ đang xử lý vượt hạn mức " + dong(limit)
                            + " đồng; số tiền còn có thể đề nghị: " + dong(remaining) + " đồng");
        }
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String dong(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,d", amount.setScale(0, RoundingMode.DOWN).longValueExact()).replace(',', '.');
    }
}
