package com.finora.investment.service.impl;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.restructure.InvestorRescheduleConsent;
import com.finora.investment.domain.restructure.InvestorRescheduleConsentStatus;
import com.finora.investment.dto.response.InvestorRescheduleConsentResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.LoanRescheduleConsentCompletedEventData;
import com.finora.investment.repository.InvestorRescheduleConsentRepository;
import com.finora.investment.service.InvestorRescheduleConsentService;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InvestorRescheduleConsentServiceImpl implements InvestorRescheduleConsentService {

    private final InvestorRescheduleConsentRepository consents;
    private final InvestmentOutboxService outbox;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InvestorRescheduleConsentResponse> listMine(int page, int size) {
        Page<InvestorRescheduleConsentResponse> result = consents
                .findByInvestorIdOrderByCreatedAtDesc(
                        SecurityUtils.getCurrentUserId(), PageRequest.of(page, size))
                .map(this::response);
        return PageResponse.<InvestorRescheduleConsentResponse>builder()
                .content(result.getContent())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public InvestorRescheduleConsentResponse detail(UUID requestId) {
        return response(owned(requestId, SecurityUtils.getCurrentUserId()));
    }

    @Override
    @Transactional
    public InvestorRescheduleConsentResponse decide(UUID requestId, String idempotencyKey,
            boolean approve, String comment) {
        String investorId = SecurityUtils.getCurrentUserId();
        List<InvestorRescheduleConsent> all = consents.findAllByRequestIdForUpdate(requestId);
        InvestorRescheduleConsent mine = all.stream().filter(value -> value.getInvestorId().equals(investorId))
                .findFirst().orElseThrow(() -> InvestmentDomainException.notFound(
                        "RESCHEDULE_CONSENT_NOT_FOUND", "Không tìm thấy đề nghị cơ cấu của bạn"));
        if (mine.sameDecision(idempotencyKey)) return response(mine);
        if (mine.getStatus() != InvestorRescheduleConsentStatus.PENDING) {
            throw InvestmentDomainException.conflict("RESCHEDULE_CONSENT_ALREADY_DECIDED",
                    "Đề nghị cơ cấu không còn chờ quyết định");
        }
        Instant now = clock.instant();
        if (approve) mine.approve(idempotencyKey, comment, now);
        else mine.reject(idempotencyKey, comment, now);

        int approved = (int) all.stream()
                .filter(value -> value.getStatus() == InvestorRescheduleConsentStatus.APPROVED).count();
        boolean resolved = !approve || approved == all.size();
        if (resolved) {
            if (!approve) all.stream().filter(value -> value != mine).forEach(value -> value.cancel(now));
            outbox.record("LoanRescheduleConsent", requestId.toString(),
                    "LoanRescheduleConsentCompleted", 1,
                    new LoanRescheduleConsentCompletedEventData(requestId, mine.getLoanApplicationId(),
                            approve, all.size(), approved, now));
        }
        return response(mine);
    }

    private InvestorRescheduleConsent owned(UUID requestId, String investorId) {
        return consents.findByRequestIdAndInvestorId(requestId, investorId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "RESCHEDULE_CONSENT_NOT_FOUND", "Không tìm thấy đề nghị cơ cấu của bạn"));
    }

    private InvestorRescheduleConsentResponse response(InvestorRescheduleConsent value) {
        return new InvestorRescheduleConsentResponse(value.getRequestId(), value.getLoanApplicationId(),
                value.getLoanNumber(), value.getRequestType(), value.getRescheduleFromDate(),
                value.getAdjustedDueDate(), value.getExtraTerms(), value.getOriginalMaturityDate(),
                value.getOutstandingPrincipal(), value.getStatus(), value.getDecisionComment(),
                value.getDecidedAt(), value.getCreatedAt());
    }
}
