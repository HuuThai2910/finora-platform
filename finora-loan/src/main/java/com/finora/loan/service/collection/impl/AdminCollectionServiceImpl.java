package com.finora.loan.service.collection.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.collection.entity.*;
import com.finora.loan.domain.collection.enums.*;
import com.finora.loan.dto.collection.request.CreateCollectionActionRequest;
import com.finora.loan.dto.collection.response.CollectionActionResponse;
import com.finora.loan.dto.collection.response.CollectionCaseResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.collection.LoanCollectionActionRepository;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.service.collection.AdminCollectionService;
import com.finora.loan.util.HashingService;
import com.finora.loan.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Màn thu hồi nợ của admin: xem hồ sơ thu hồi, lịch sử hành động và ghi hành động thu hồi mới.
 *
 * <p>Idempotency theo cặp người thao tác + {@code Idempotency-Key}, so bằng hash nội dung yêu cầu; hồ sơ thu hồi được
 * khóa trước khi thêm hành động.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminCollectionServiceImpl implements AdminCollectionService {
    private final LoanCollectionCaseRepository loanCollectionCaseRepository;
    private final LoanCollectionActionRepository loanCollectionActionRepository;
    private final HashingService hashingService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CollectionCaseResponse> list(CollectionCaseStatus status, CollectionStage stage,
            int page, int size) {
        return list(status, stage, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CollectionCaseResponse> list(CollectionCaseStatus status, CollectionStage stage, String q,
            int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<LoanCollectionCase> result;
        String code = SearchPatterns.containsCode(q);
        if (code != null) {
            result = loanCollectionCaseRepository.search(status, stage, code, pageable);
        } else if (status != null && stage != null) {
            result = loanCollectionCaseRepository.findByStatusAndStageOrderByDaysPastDueDescUpdatedAtAsc(
                    status, stage, pageable);
        } else if (status != null) {
            result = loanCollectionCaseRepository.findByStatusOrderByDaysPastDueDescUpdatedAtAsc(status, pageable);
        } else if (stage != null) {
            result = loanCollectionCaseRepository.findByStageOrderByDaysPastDueDescUpdatedAtAsc(stage, pageable);
        } else {
            result = loanCollectionCaseRepository.findAllByOrderByDaysPastDueDescUpdatedAtAsc(pageable);
        }
        return PageResponse.from(result.map(this::caseResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CollectionCaseResponse> listByLoan(String loanNumber, CollectionCaseStatus status,
            int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        String value = loanNumber.trim();
        Page<LoanCollectionCase> result = status == null
                ? loanCollectionCaseRepository.findByLoanNumberOrderByOpenedAtDesc(value, pageable)
                : loanCollectionCaseRepository.findByLoanNumberAndStatusOrderByOpenedAtDesc(value, status, pageable);
        return PageResponse.from(result.map(this::caseResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CollectionActionResponse> actions(UUID caseId, int page, int size) {
        LoanCollectionCase collectionCase = loanCollectionCaseRepository.findByCaseId(caseId)
                .orElseThrow(() -> notFound());
        return PageResponse.from(loanCollectionActionRepository.findByCollectionCaseIdOrderByCreatedAtDesc(
                collectionCase.getId(), PageRequest.of(page, size)).map(this::actionResponse));
    }

    @Override
    @Transactional
    public CollectionActionResponse record(UUID caseId, String idempotencyKey,
            CreateCollectionActionRequest request) {
        String actorId = SecurityUtils.getCurrentUserId();
        String requestHash = hashingService.sha256(requestPayload(caseId, request));
        LoanCollectionAction duplicate = loanCollectionActionRepository
                .findByActorIdAndIdempotencyKey(actorId, idempotencyKey)
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.getRequestHash().equals(requestHash)) {
                throw LoanBusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key đã được dùng cho một hành động thu hồi khác");
            }
            return actionResponse(duplicate);
        }
        LoanCollectionCase collectionCase = loanCollectionCaseRepository.findByCaseIdForUpdate(caseId)
                .orElseThrow(() -> notFound());
        if (collectionCase.getStatus() != CollectionCaseStatus.OPEN) {
            throw LoanBusinessException.conflict("COLLECTION_CASE_CLOSED",
                    "Hồ sơ thu hồi đã đóng, không thể thêm hành động mới");
        }
        LoanCollectionAction action;
        try {
            action = LoanCollectionAction.record(collectionCase.getId(), request.actionType(), request.note(),
                    request.promiseDate(), request.promiseAmount(), actorId, idempotencyKey, requestHash,
                    clock.instant());
        } catch (IllegalArgumentException exception) {
            throw LoanBusinessException.badRequest("COLLECTION_ACTION_INVALID", exception.getMessage());
        }
        return actionResponse(loanCollectionActionRepository.save(action));
    }

    private Map<String, Object> requestPayload(UUID caseId, CreateCollectionActionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", caseId);
        payload.put("actionType", request.actionType());
        payload.put("note", request.note() == null ? "" : request.note());
        payload.put("promiseDate", request.promiseDate() == null ? "" : request.promiseDate());
        payload.put("promiseAmount", request.promiseAmount() == null ? "" : request.promiseAmount());
        return payload;
    }

    private CollectionCaseResponse caseResponse(LoanCollectionCase collectionCase) {
        return new CollectionCaseResponse(collectionCase.getCaseId(), collectionCase.getLoanNumber(),
                collectionCase.getBorrowerId(), collectionCase.getStage(), collectionCase.getStatus(),
                collectionCase.getDaysPastDue(), collectionCase.getDebtGroup(), collectionCase.getOverdueAmount(),
                collectionCase.getTotalOutstanding(), collectionCase.getOverdueSince(), collectionCase.getOpenedAt(),
                collectionCase.getLastObservedAt(), collectionCase.getClosedAt());
    }
    private CollectionActionResponse actionResponse(LoanCollectionAction action) {
        return new CollectionActionResponse(action.getActionId(), action.getActionType(), action.getNote(),
                action.getPromiseDate(), action.getPromiseAmount(), action.getActorId(), action.getCreatedAt());
    }
    private static LoanBusinessException notFound() {
        return new LoanBusinessException(HttpStatus.NOT_FOUND, "COLLECTION_CASE_NOT_FOUND",
                "Không tìm thấy hồ sơ thu hồi");
    }
}
