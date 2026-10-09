package com.finora.loan.service.application;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationPurpose;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.scoring.BorrowerCreditProfile;
import com.finora.loan.domain.scoring.BorrowerEligibilityCheck;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hồ sơ người vay cho nhà đầu tư trước khi góp vốn (NĐ 94/2025 Điều 22 khoản 3 và khoản 8 điểm c:
 * cung cấp đầy đủ thông tin khoản vay trước khi khách hàng giao kết).
 *
 * <p>Chỉ hồ sơ đã được đưa lên sàn ({@code fundingStatus != null}) mới xem được. Hồ sơ chưa lên sàn trả
 * cùng lỗi 404 như hồ sơ không tồn tại, để không dò được hồ sơ đang thẩm định. Hồ sơ đã đủ vốn vẫn xem
 * được vì Note của nó còn được mua bán trên chợ thứ cấp.</p>
 *
 * <p>Số query cố định (tối đa 6), chỉ đọc snapshot local; không gọi User, AI hay Fineract.</p>
 */
@Service
@RequiredArgsConstructor
public class InvestorBorrowerProfileService {

    private static final String INVESTOR_ROLE = "ROLE_INVESTOR";

    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final BorrowerEligibilityCheckRepository eligibilityRepository;
    private final BorrowerCreditProfileRepository creditProfileRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final StoredAiCreditResponseReader snapshotReader;
    private final InvestorBorrowerProfileMapper mapper;

    @Transactional(readOnly = true)
    public InvestorBorrowerProfileResponse profile(String applicationNumber) {
        // Kiểm ở service (không chặn theo URL) để người không đủ quyền nhận lỗi JSON có mã, giống
        // requireAdmin; handler mặc định của Spring trả 403 rỗng, app sẽ đọc nhầm thành hết phiên.
        if (!SecurityUtils.hasRole(INVESTOR_ROLE)) {
            throw LoanBusinessException.forbidden(
                    "INVESTOR_ROLE_REQUIRED", "Chỉ tài khoản nhà đầu tư mới xem được hồ sơ người vay");
        }
        LoanApplication application = applicationRepository.findByApplicationNumber(applicationNumber)
                .filter(found -> found.getFundingStatus() != null)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Không tìm thấy hồ sơ người vay của khoản vay này trên sàn"));

        // Lịch cuối (CONTRACT) là điều khoản người vay đã được phép gọi vốn; hồ sơ cũ chưa có thì
        // dùng lịch lúc nộp để vẫn có kỳ trả tham khảo.
        ScheduleCalculationSnapshot schedule = scheduleRepository
                .findByApplicationIdAndPurpose(application.getId(), ScheduleCalculationPurpose.CONTRACT)
                .or(() -> scheduleRepository.findByApplicationIdAndPurpose(
                        application.getId(), ScheduleCalculationPurpose.SUBMISSION_SCORING))
                .orElse(null);
        BorrowerEligibilityCheck eligibility = eligibilityRepository
                .findFirstByApplicationIdOrderByCreatedAtDescIdDesc(application.getId())
                .orElse(null);
        BorrowerCreditProfile creditProfile = creditProfileRepository
                .findByBorrowerId(application.getBorrowerId())
                .orElse(null);
        CreditScoringAssessment assessment = application.getLatestCreditAssessmentId() == null
                ? null
                : assessmentRepository.findByIdAndApplicationId(
                        application.getLatestCreditAssessmentId(), application.getId()).orElse(null);
        StoredAiCreditResponse snapshot = assessment == null ? null : snapshotReader.read(assessment);

        return mapper.toResponse(application, schedule, eligibility, creditProfile, assessment,
                snapshot == null ? null : snapshot.ruleTrace());
    }
}
