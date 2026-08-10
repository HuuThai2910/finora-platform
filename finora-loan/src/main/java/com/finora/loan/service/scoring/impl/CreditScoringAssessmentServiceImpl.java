package com.finora.loan.service.scoring.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.config.MockCurrentUserProvider;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.scoring.CreditScoringAssessment;
import com.finora.loan.dto.common.PageResponse;
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

@Service
@RequiredArgsConstructor
public class CreditScoringAssessmentServiceImpl implements CreditScoringAssessmentService {

    private final LoanApplicationRepository applicationRepository;
    private final CreditScoringAssessmentRepository assessmentRepository;
    private final CreditScoringAssessmentMapper mapper;
    private final CreditScoringAdminStateService stateService;
    private final MockCurrentUserProvider currentUser;

    /** Một query Application và một query assessment page; không tải history/snapshot khác theo từng dòng. */
    @Override
    @Transactional(readOnly = true)
    public PageResponse<CreditAssessmentSummaryResponse> list(String applicationNumber, int page, int size) {
        LoanApplication application = application(applicationNumber);
        Page<CreditAssessmentSummaryResponse> result = assessmentRepository.findByApplicationId(
                        application.getId(),
                        PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(mapper::toSummary);
        return PageResponse.from(result);
    }

    @Override
    @Transactional(readOnly = true)
    /** Assessment phải thuộc Application trên URL, không cho phép tra cứu chéo bằng ID đoán được. */
    public CreditAssessmentDetailResponse detail(String applicationNumber, Long assessmentId) {
        LoanApplication application = application(applicationNumber);
        CreditScoringAssessment assessment = assessmentRepository.findByIdAndApplicationId(
                        assessmentId, application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", assessmentId));
        return mapper.toDetail(assessment);
    }

    @Override
    /** Chỉ trả biên nhận tiếp nhận; kết quả bất đồng bộ được đọc qua API chi tiết assessment. */
    public ScoringRetryAcceptedResponse retry(
            String applicationNumber,
            String idempotencyKey,
            ScoringRetryRequest request
    ) {
        CreditScoringAssessment assessment = stateService.retry(
                applicationNumber, idempotencyKey.trim(), request, currentUser.adminUserId());
        return ScoringRetryAcceptedResponse.accepted(
                assessment.getId(), assessment.getStatus(), applicationNumber);
    }

    private LoanApplication application(String applicationNumber) {
        return applicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }
}
