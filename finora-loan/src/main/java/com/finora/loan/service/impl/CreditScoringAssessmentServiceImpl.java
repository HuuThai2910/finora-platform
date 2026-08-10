package com.finora.loan.service.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.CreditScoringAssessment;
import com.finora.loan.domain.LoanApplication;
import com.finora.loan.dto.request.ScoringRetryRequest;
import com.finora.loan.dto.response.CreditAssessmentDetailResponse;
import com.finora.loan.dto.response.CreditAssessmentSummaryResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.mapper.CreditScoringAssessmentMapper;
import com.finora.loan.repository.CreditScoringAssessmentRepository;
import com.finora.loan.repository.LoanApplicationRepository;
import com.finora.loan.service.CreditScoringAdminStateService;
import com.finora.loan.service.CreditScoringAssessmentService;
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
    public CreditAssessmentDetailResponse detail(String applicationNumber, Long assessmentId) {
        LoanApplication application = application(applicationNumber);
        CreditScoringAssessment assessment = assessmentRepository.findByIdAndApplicationId(
                        assessmentId, application.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Credit Scoring Assessment", "id", assessmentId));
        return mapper.toDetail(assessment);
    }

    @Override
    public CreditAssessmentDetailResponse retry(
            String applicationNumber,
            String idempotencyKey,
            ScoringRetryRequest request
    ) {
        return mapper.toDetail(stateService.retry(applicationNumber, idempotencyKey.trim(), request));
    }

    private LoanApplication application(String applicationNumber) {
        return applicationRepository.findByApplicationNumber(applicationNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Loan Application", "applicationNumber", applicationNumber));
    }
}
