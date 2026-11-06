package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.core.enums.ScheduleCalculationPurpose;
import com.finora.loan.domain.scoring.entity.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.entity.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.dto.application.response.InvestorBorrowerProfileResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.ai.contract.StoredAiCreditResponse;
import com.finora.loan.mapper.application.InvestorBorrowerProfileMapper;
import com.finora.loan.mapper.scoring.StoredAiCreditResponseReader;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.scoring.BorrowerCreditProfileRepository;
import com.finora.loan.repository.scoring.BorrowerEligibilityCheckRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.application.InvestorBorrowerProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hồ sơ người vay cho nhà đầu tư trước khi góp vốn (NĐ 94/2025 Điều 22 khoản 3 và khoản 8 điểm c:
 * cung cấp đầy đủ thông tin khoản vay trước khi khách hàng giao kết).
 *
 * <p>Số query cố định (tối đa 6), chỉ đọc snapshot local; không gọi User, AI hay Fineract.</p>
 */
@Service
@RequiredArgsConstructor
public class InvestorBorrowerProfileServiceImpl implements InvestorBorrowerProfileService {

    private static final String INVESTOR_ROLE = "ROLE_INVESTOR";

    private final LoanApplicationRepository loanApplicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final BorrowerEligibilityCheckRepository borrowerEligibilityCheckRepository;
    private final BorrowerCreditProfileRepository borrowerCreditProfileRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final StoredAiCreditResponseReader storedAiCreditResponseReader;
    private final InvestorBorrowerProfileMapper investorBorrowerProfileMapper;

    @Override
    @Transactional(readOnly = true)
    public InvestorBorrowerProfileResponse profile(String applicationNumber) {
        // Kiểm ở service (không chặn theo URL) để người không đủ quyền nhận lỗi JSON có mã, giống
        // requireAdmin; handler mặc định của Spring trả 403 rỗng, app sẽ đọc nhầm thành hết phiên.
        if (!SecurityUtils.hasRole(INVESTOR_ROLE)) {
            throw LoanBusinessException.forbidden(
                    "INVESTOR_ROLE_REQUIRED", "Chỉ tài khoản nhà đầu tư mới xem được hồ sơ người vay");
        }
        LoanApplication application = loanApplicationRepository.findByApplicationNumber(applicationNumber)
                .filter(found -> found.getFundingStatus() != null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy hồ sơ người vay của khoản vay này trên sàn"));

        // Lịch cuối (CONTRACT) là điều khoản người vay đã được phép gọi vốn; hồ sơ cũ chưa có thì
        // dùng lịch lúc nộp để vẫn có kỳ trả tham khảo.
        ScheduleCalculationSnapshot schedule = scheduleCalculationSnapshotRepository
                .findByApplicationIdAndPurpose(application.getId(), ScheduleCalculationPurpose.CONTRACT)
                .or(() -> scheduleCalculationSnapshotRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .orElse(null);
        BorrowerEligibilityCheck eligibility = borrowerEligibilityCheckRepository
                .findFirstByApplicationIdOrderByCreatedAtDescIdDesc(application.getId())
                .orElse(null);
        BorrowerCreditProfile creditProfile = borrowerCreditProfileRepository
                .findByBorrowerId(application.getBorrowerId())
                .orElse(null);
        CreditScoringAssessment assessment = application.getLatestCreditAssessmentId() == null
                ? null
                : creditScoringAssessmentRepository.findByIdAndApplicationId(
                        application.getLatestCreditAssessmentId(), application.getId()).orElse(null);
        StoredAiCreditResponse snapshot = assessment == null ? null : storedAiCreditResponseReader.read(assessment);

        return investorBorrowerProfileMapper.toResponse(application, schedule, eligibility, creditProfile, assessment,
                snapshot == null ? null : snapshot.ruleTrace());
    }
}
