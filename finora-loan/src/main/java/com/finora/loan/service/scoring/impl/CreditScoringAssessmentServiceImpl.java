package com.finora.loan.service.scoring.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.dto.scoring.request.ScoringRetryRequest;
import com.finora.loan.dto.scoring.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.scoring.response.CreditAssessmentSummaryResponse;
import com.finora.loan.dto.scoring.response.ScoringRetryAcceptedResponse;
import com.finora.loan.mapper.scoring.CreditScoringAssessmentMapper;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.scoring.CreditScoringAssessmentRepository;
import com.finora.loan.service.scoring.CreditScoringAdminStateService;
import com.finora.loan.service.scoring.CreditScoringAssessmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tra cứu assessment chấm điểm của một hồ sơ và yêu cầu chấm lại thủ công cho màn quản trị.
 * <p>
 * Assessment luôn được tìm trong phạm vi hồ sơ trên URL; yêu cầu chấm lại chỉ trả biên nhận, kết quả đọc lại qua API
 * chi tiết.
 */
@Service
@RequiredArgsConstructor
public class CreditScoringAssessmentServiceImpl implements CreditScoringAssessmentService {

    private final LoanApplicationRepository loanApplicationRepository;
    private final CreditScoringAssessmentRepository creditScoringAssessmentRepository;
    private final CreditScoringAssessmentMapper creditScoringAssessmentMapper;
    private final CreditScoringAdminStateService creditScoringAdminStateService;

    /** Một query Application và một query assessment page; không tải history/snapshot khác theo từng dòng. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<CreditAssessmentSummaryResponse> list(String applicationNumber, int page, int size) {
        LoanApplication application = findApplicationOrThrow(applicationNumber);
        Page<CreditAssessmentSummaryResponse> result = creditScoringAssessmentRepository.findByApplicationId(
                        application.getId(),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(creditScoringAssessmentMapper::toSummary);
        return PageResponse.from(result);
    }

    /** Assessment phải thuộc Application trên URL, không cho phép tra cứu chéo bằng ID đoán được. */
    @Override
    @Transactional(readOnly = true)
    public CreditAssessmentDetailResponse detail(String applicationNumber, Long assessmentId) {
        LoanApplication application = findApplicationOrThrow(applicationNumber);
        CreditScoringAssessment assessment = creditScoringAssessmentRepository.findByIdAndApplicationId(
                        assessmentId, application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", assessmentId));
        return creditScoringAssessmentMapper.toDetail(assessment);
    }

    /** Chỉ trả biên nhận tiếp nhận; kết quả bất đồng bộ được đọc qua API chi tiết assessment. */
    @Override
    public ScoringRetryAcceptedResponse retry(
            String applicationNumber,
            String idempotencyKey,
            ScoringRetryRequest request
    ) {
        SecurityUtils.requirePermission(OpsFunction.LOAN_APPLICATION.action());
        CreditScoringAssessment assessment = creditScoringAdminStateService.retry(
                applicationNumber, idempotencyKey.trim(), request, SecurityUtils.getCurrentUserId());
        return ScoringRetryAcceptedResponse.accepted(
                assessment.getId(), assessment.getStatus(), applicationNumber);
    }

    private LoanApplication findApplicationOrThrow(String applicationNumber) {
        return loanApplicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }
}
