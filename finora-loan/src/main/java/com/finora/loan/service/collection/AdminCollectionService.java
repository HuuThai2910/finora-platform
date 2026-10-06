package com.finora.loan.service.collection;

import com.finora.common.security.SecurityUtils;
import com.finora.loan.domain.collection.*;
import com.finora.loan.dto.collection.request.CreateCollectionActionRequest;
import com.finora.loan.dto.collection.response.CollectionActionResponse;
import com.finora.loan.dto.collection.response.CollectionCaseResponse;
import com.finora.loan.dto.common.PageResponse;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.collection.LoanCollectionActionRepository;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminCollectionService {
    private final LoanCollectionCaseRepository cases;
    private final LoanCollectionActionRepository actions;
    private final HashingService hashing;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<CollectionCaseResponse> list(CollectionCaseStatus status, CollectionStage stage,
            int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<LoanCollectionCase> result;
        if (status != null && stage != null) {
            result = cases.findByStatusAndStageOrderByDaysPastDueDescUpdatedAtAsc(status, stage, pageable);
        } else if (status != null) {
            result = cases.findByStatusOrderByDaysPastDueDescUpdatedAtAsc(status, pageable);
        } else if (stage != null) {
            result = cases.findByStageOrderByDaysPastDueDescUpdatedAtAsc(stage, pageable);
        } else {
            result = cases.findAllByOrderByDaysPastDueDescUpdatedAtAsc(pageable);
        }
        return PageResponse.from(result.map(this::caseResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<CollectionActionResponse> actions(UUID caseId, int page, int size) {
        LoanCollectionCase value = cases.findByCaseId(caseId).orElseThrow(() -> notFound());
        return PageResponse.from(actions.findByCollectionCaseIdOrderByCreatedAtDesc(
                value.getId(), PageRequest.of(page, size)).map(this::actionResponse));
    }

    @Transactional
    public CollectionActionResponse record(UUID caseId, String idempotencyKey,
            CreateCollectionActionRequest input) {
        String actorId = SecurityUtils.getCurrentUserId();
        String requestHash = hashing.sha256(requestPayload(caseId, input));
        LoanCollectionAction duplicate = actions.findByActorIdAndIdempotencyKey(actorId, idempotencyKey)
                .orElse(null);
        if (duplicate != null) {
            if (!duplicate.getRequestHash().equals(requestHash)) {
                throw LoanBusinessException.conflict("IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key đã được dùng cho một hành động thu hồi khác");
            }
            return actionResponse(duplicate);
        }
        LoanCollectionCase value = cases.findByCaseIdForUpdate(caseId).orElseThrow(() -> notFound());
        if (value.getStatus() != CollectionCaseStatus.OPEN) {
            throw LoanBusinessException.conflict("COLLECTION_CASE_CLOSED",
                    "Hồ sơ thu hồi đã đóng, không thể thêm hành động mới");
        }
        LoanCollectionAction action;
        try {
            action = LoanCollectionAction.record(value.getId(), input.actionType(), input.note(),
                    input.promiseDate(), input.promiseAmount(), actorId, idempotencyKey, requestHash, clock.instant());
        } catch (IllegalArgumentException exception) {
            throw LoanBusinessException.badRequest("COLLECTION_ACTION_INVALID", exception.getMessage());
        }
        return actionResponse(actions.save(action));
    }

    private Map<String, Object> requestPayload(UUID caseId, CreateCollectionActionRequest input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("caseId", caseId);
        value.put("actionType", input.actionType());
        value.put("note", input.note() == null ? "" : input.note());
        value.put("promiseDate", input.promiseDate() == null ? "" : input.promiseDate());
        value.put("promiseAmount", input.promiseAmount() == null ? "" : input.promiseAmount());
        return value;
    }

    private CollectionCaseResponse caseResponse(LoanCollectionCase value) {
        return new CollectionCaseResponse(value.getCaseId(), value.getLoanNumber(), value.getBorrowerId(),
                value.getStage(), value.getStatus(), value.getDaysPastDue(), value.getDebtGroup(),
                value.getOverdueAmount(), value.getTotalOutstanding(), value.getOverdueSince(),
                value.getOpenedAt(), value.getLastObservedAt(), value.getClosedAt());
    }
    private CollectionActionResponse actionResponse(LoanCollectionAction value) {
        return new CollectionActionResponse(value.getActionId(), value.getActionType(), value.getNote(),
                value.getPromiseDate(), value.getPromiseAmount(), value.getActorId(), value.getCreatedAt());
    }
    private static LoanBusinessException notFound() {
        return new LoanBusinessException(HttpStatus.NOT_FOUND, "COLLECTION_CASE_NOT_FOUND",
                "Không tìm thấy hồ sơ thu hồi");
    }
}

