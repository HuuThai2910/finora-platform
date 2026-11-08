package com.finora.loan.service.application.impl;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.entity.LoanApplicationStatusHistory;
import com.finora.loan.domain.application.enums.ActorType;
import com.finora.loan.domain.application.enums.TermsConfirmationStatus;
import com.finora.loan.domain.collection.entity.LoanCollectionAction;
import com.finora.loan.domain.collection.entity.LoanCollectionCase;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.entity.LoanContractStatusHistory;
import com.finora.loan.domain.contract.enums.ContractPartyStatus;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.restructure.entity.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.dto.application.response.LoanApplicationTimelineItemResponse;
import com.finora.loan.repository.application.LoanApplicationStatusHistoryRepository;
import com.finora.loan.repository.collection.LoanCollectionActionRepository;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import com.finora.loan.repository.contract.ContractPartyRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.contract.LoanContractStatusHistoryRepository;
import com.finora.loan.repository.disbursement.DisbursementSagaRepository;
import com.finora.loan.repository.restructure.LoanRescheduleRequestRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.service.application.LoanApplicationTimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ghép lịch sử xử lý từ các bảng nguồn của Loan thay vì một bảng nhật ký riêng: hồ sơ cũ (trước tính năng này) vẫn có
 * đủ mốc, và không có chỗ nào phải nhớ ghi thêm một dòng nhật ký song song với dòng history thật.
 *
 * <p>Mỗi nguồn được đọc bằng một query theo khóa của hồ sơ/khoản vay (không N+1). Số dòng có giới hạn vì màn hình chỉ
 * cần đủ để đọc một hồ sơ.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanApplicationTimelineServiceImpl implements LoanApplicationTimelineService {

    private static final int SOURCE_LIMIT = 200;
    private static final Set<LoanRescheduleStatus> RESTRUCTURE_TERMINAL = Set.of(
            LoanRescheduleStatus.COMPLETED, LoanRescheduleStatus.INVESTOR_REJECTED,
            LoanRescheduleStatus.RECONCILIATION_REQUIRED, LoanRescheduleStatus.MANUAL_REVIEW);

    private final LoanApplicationStatusHistoryRepository loanApplicationStatusHistoryRepository;
    private final LoanContractRepository loanContractRepository;
    private final LoanContractStatusHistoryRepository loanContractStatusHistoryRepository;
    private final ContractPartyRepository contractPartyRepository;
    private final DisbursementSagaRepository disbursementSagaRepository;
    private final FinoraLoanRepository finoraLoanRepository;
    private final LoanRescheduleRequestRepository loanRescheduleRequestRepository;
    private final LoanCollectionCaseRepository loanCollectionCaseRepository;
    private final LoanCollectionActionRepository loanCollectionActionRepository;

    @Override
    @Transactional(readOnly = true)
    public List<LoanApplicationTimelineItemResponse> timeline(LoanApplication application) {
        List<LoanApplicationTimelineItemResponse> items = new ArrayList<>();
        applicationHistory(application, items);
        terms(application, items);
        funding(application, items);
        loanContractRepository.findByApplicationId(application.getId())
                .ifPresent(contract -> contract(contract, items));
        disbursementSagaRepository.findByLoanApplicationId(application.getId())
                .ifPresent(saga -> disbursement(saga, items));
        finoraLoanRepository.findByLoanApplicationId(application.getId())
                .ifPresent(loan -> servicing(loan, items));
        // Mới nhất trước; cùng thời điểm thì giữ thứ tự nghiệp vụ đã thêm (đảo lại để mốc thêm sau đứng trên).
        List<LoanApplicationTimelineItemResponse> ordered = new ArrayList<>(items);
        Collections.reverse(ordered);
        ordered.sort(Comparator.comparing(LoanApplicationTimelineItemResponse::occurredAt).reversed());
        return ordered;
    }

    private void applicationHistory(LoanApplication application, List<LoanApplicationTimelineItemResponse> items) {
        List<LoanApplicationStatusHistory> history = loanApplicationStatusHistoryRepository.findByLoanApplicationId(
                        application.getId(),
                        PageRequest.of(0, SOURCE_LIMIT, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))))
                .getContent();
        for (LoanApplicationStatusHistory row : history) {
            // Rút khi đang gọi vốn giữ hồ sơ APPROVED: không phải chuyển trạng thái nên dùng mã lý do làm loại mốc.
            boolean sameStatus = row.getFromStatus() != null && row.getFromStatus() == row.getToStatus();
            items.add(new LoanApplicationTimelineItemResponse(
                    "application-" + row.getId(), row.getCreatedAt(),
                    sameStatus ? "FUNDING" : "APPLICATION",
                    sameStatus && row.getReasonCode() != null ? row.getReasonCode() : "APPLICATION_STATUS",
                    row.getFromStatus() == null ? null : row.getFromStatus().name(), row.getToStatus().name(),
                    row.getReasonCode(), row.getReasonDetail(), row.getActorType(), row.getActorId(), null));
        }
    }

    private void terms(LoanApplication application, List<LoanApplicationTimelineItemResponse> items) {
        TermsConfirmationStatus status = application.getTermsConfirmationStatus();
        if (status == TermsConfirmationStatus.ACCEPTED || status == TermsConfirmationStatus.DECLINED) {
            if (application.getTermsRespondedAt() != null) {
                items.add(new LoanApplicationTimelineItemResponse(
                        "terms-" + application.getId(), application.getTermsRespondedAt(), "APPLICATION",
                        status == TermsConfirmationStatus.ACCEPTED ? "TERMS_ACCEPTED" : "TERMS_DECLINED",
                        null, status.name(), application.getTermsDeclineReasonCode(), null,
                        ActorType.BORROWER, application.getTermsRespondedBy(), null));
            }
        } else if (status == TermsConfirmationStatus.EXPIRED && application.getTermsExpiresAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "terms-" + application.getId(), application.getTermsExpiresAt(), "APPLICATION",
                    "TERMS_EXPIRED", null, status.name(), null, null, ActorType.SYSTEM, "SYSTEM", null));
        }
    }

    private void funding(LoanApplication application, List<LoanApplicationTimelineItemResponse> items) {
        if (application.getFundingRequestedAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "funding-requested-" + application.getId(), application.getFundingRequestedAt(), "FUNDING",
                    "FUNDING_REQUESTED", null, null, null, null, ActorType.SYSTEM, "SYSTEM",
                    application.getRequestedAmount()));
        }
        if (application.getFundingExpiredAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "funding-expired-" + application.getId(), application.getFundingExpiredAt(), "FUNDING",
                    "FUNDING_EXPIRED", null, null, null, null, ActorType.SYSTEM, "SYSTEM", null));
        }
        if (application.getFundingCancelledAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "funding-cancelled-" + application.getId(), application.getFundingCancelledAt(), "FUNDING",
                    "FUNDING_CANCELLED", null, null, application.getFundingCancelReason(), null,
                    ActorType.SYSTEM, "SYSTEM", null));
        }
    }

    private void contract(LoanContract contract, List<LoanApplicationTimelineItemResponse> items) {
        List<LoanContractStatusHistory> history = loanContractStatusHistoryRepository.findByContractId(
                        contract.getId(),
                        PageRequest.of(0, SOURCE_LIMIT, Sort.by(Sort.Order.asc("occurredAt"), Sort.Order.asc("id"))))
                .getContent();
        for (LoanContractStatusHistory row : history) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "contract-" + row.getId(), row.getOccurredAt(), "CONTRACT", "CONTRACT_STATUS",
                    row.getFromStatus() == null ? null : row.getFromStatus().name(), row.getToStatus().name(),
                    row.getReasonCode(), contract.getContractNumber(), row.getActorType(), row.getActorId(), null));
        }
        // Mỗi nhà đầu tư ký một lần cho mọi phần vốn của mình: gộp phần vốn theo (nhà đầu tư, thời điểm).
        Map<String, LoanApplicationTimelineItemResponse> lenderEvents = new LinkedHashMap<>();
        for (ContractParty party : contractPartyRepository.findByContractIdOrderByPartyTypeAscCommitmentIdAsc(
                contract.getId())) {
            if (party.getCommitmentId() == null) {
                continue;
            }
            if (party.getStatus() == ContractPartyStatus.SIGNED && party.getSignedAt() != null) {
                mergeLender(lenderEvents, "LENDER_SIGNED", party, party.getSignedAt());
            } else if ((party.getStatus() == ContractPartyStatus.DECLINED
                    || party.getStatus() == ContractPartyStatus.EXPIRED) && party.getClosedAt() != null) {
                mergeLender(lenderEvents, party.getStatus() == ContractPartyStatus.DECLINED
                        ? "LENDER_DECLINED" : "LENDER_EXPIRED", party, party.getClosedAt());
            }
        }
        items.addAll(lenderEvents.values());
    }

    private static void mergeLender(
            Map<String, LoanApplicationTimelineItemResponse> events,
            String eventCode,
            ContractParty party,
            Instant occurredAt
    ) {
        String key = "lender-" + eventCode + "-" + party.getPartyId() + "-" + occurredAt.toEpochMilli();
        LoanApplicationTimelineItemResponse current = events.get(key);
        BigDecimal amount = current == null
                ? party.getAllocationAmount()
                : current.amount().add(party.getAllocationAmount());
        events.put(key, new LoanApplicationTimelineItemResponse(
                key, occurredAt, "CONTRACT", eventCode, null, party.getStatus().name(), null, null,
                ActorType.INVESTOR, party.getPartyId(), amount));
    }

    private void disbursement(DisbursementSaga saga, List<LoanApplicationTimelineItemResponse> items) {
        String id = saga.getSagaId().toString();
        items.add(new LoanApplicationTimelineItemResponse(
                "disbursement-requested-" + id, saga.getCreatedAt(), "DISBURSEMENT", "DISBURSEMENT_REQUESTED",
                null, null, null, saga.getContractNumber(), ActorType.SYSTEM, "SYSTEM", saga.getAmount()));
        if (saga.getDisbursedAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "disbursement-done-" + id, saga.getDisbursedAt(), "DISBURSEMENT", "DISBURSED",
                    null, null, null, saga.getPaymentReference(), ActorType.SYSTEM, "SYSTEM", saga.getAmount()));
        }
        if (saga.getStatus() == DisbursementSagaStatus.PAYMENT_FAILED
                || saga.getStatus() == DisbursementSagaStatus.REPAIR_REQUIRED) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "disbursement-issue-" + id, saga.getUpdatedAt(), "DISBURSEMENT",
                    saga.getStatus() == DisbursementSagaStatus.PAYMENT_FAILED
                            ? "DISBURSEMENT_FAILED" : "DISBURSEMENT_REPAIR_REQUIRED",
                    null, saga.getStatus().name(), saga.getLastErrorCode(), null, ActorType.SYSTEM, "SYSTEM", null));
        }
        if (saga.getLastRepairAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "disbursement-repair-" + id, saga.getLastRepairAt(), "DISBURSEMENT", "DISBURSEMENT_REPAIRED",
                    null, null, null, saga.getLastRepairNote(), ActorType.ADMIN, saga.getLastRepairBy(), null));
        }
    }

    private void servicing(FinoraLoan loan, List<LoanApplicationTimelineItemResponse> items) {
        if (loan.getDisbursedAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "loan-active-" + loan.getId(), loan.getDisbursedAt(), "SERVICING", "LOAN_ACTIVATED",
                    null, null, null, loan.getLoanNumber(), ActorType.SYSTEM, "SYSTEM", loan.getPrincipalAmount()));
        }
        for (LoanRescheduleRequest request : loanRescheduleRequestRepository
                .findByFinoraLoanIdOrderByCreatedAtAsc(loan.getId())) {
            restructure(request, items);
        }
        List<LoanCollectionCase> cases = loanCollectionCaseRepository.findByFinoraLoanIdOrderByOpenedAtAsc(loan.getId());
        for (LoanCollectionCase collectionCase : cases) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "collection-open-" + collectionCase.getId(), collectionCase.getOpenedAt(), "COLLECTION",
                    "COLLECTION_OPENED", null, collectionCase.getStage().name(), null,
                    String.valueOf(collectionCase.getDaysPastDue()), ActorType.SYSTEM, "SYSTEM",
                    collectionCase.getOverdueAmount()));
            if (collectionCase.getClosedAt() != null) {
                items.add(new LoanApplicationTimelineItemResponse(
                        "collection-close-" + collectionCase.getId(), collectionCase.getClosedAt(), "COLLECTION",
                        "COLLECTION_CLOSED", null, collectionCase.getStatus().name(), null, null,
                        ActorType.SYSTEM, "SYSTEM", null));
            }
        }
        if (!cases.isEmpty()) {
            for (LoanCollectionAction action : loanCollectionActionRepository.findByCollectionCaseIdInOrderByCreatedAtAsc(
                    cases.stream().map(LoanCollectionCase::getId).toList())) {
                items.add(new LoanApplicationTimelineItemResponse(
                        "collection-action-" + action.getId(), action.getCreatedAt(), "COLLECTION",
                        "COLLECTION_ACTION", null, action.getActionType().name(), null, null,
                        ActorType.ADMIN, action.getActorId(), action.getPromiseAmount()));
            }
        }
        if (loan.getClosedAt() != null) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "loan-closed-" + loan.getId(), loan.getClosedAt(), "SERVICING", "LOAN_CLOSED",
                    null, loan.getStatus().name(), null, loan.getLoanNumber(), ActorType.SYSTEM, "SYSTEM", null));
        }
    }

    private static void restructure(LoanRescheduleRequest request, List<LoanApplicationTimelineItemResponse> items) {
        String id = request.getRequestId().toString();
        items.add(new LoanApplicationTimelineItemResponse(
                "restructure-requested-" + id, request.getCreatedAt(), "RESTRUCTURE", "RESTRUCTURE_REQUESTED",
                null, request.getRequestType().name(), null,
                request.getExtraTerms() == null ? null : String.valueOf(request.getExtraTerms()),
                ActorType.BORROWER, request.getBorrowerId(), null));
        if (request.getDecidedAt() != null) {
            boolean rejected = request.getStatus() == LoanRescheduleStatus.REJECTED;
            items.add(new LoanApplicationTimelineItemResponse(
                    "restructure-decided-" + id, request.getDecidedAt(), "RESTRUCTURE",
                    rejected ? "RESTRUCTURE_REJECTED" : "RESTRUCTURE_APPROVED",
                    null, request.getRequestType().name(), null, request.getDecisionComment(),
                    ActorType.ADMIN, request.getAdminId(), null));
        }
        // Bảng yêu cầu chỉ giữ trạng thái hiện tại: mốc kết thúc lấy thời điểm cập nhật cuối của yêu cầu.
        if (RESTRUCTURE_TERMINAL.contains(request.getStatus())) {
            items.add(new LoanApplicationTimelineItemResponse(
                    "restructure-result-" + id, request.getUpdatedAt(), "RESTRUCTURE",
                    "RESTRUCTURE_" + request.getStatus().name(), null, request.getRequestType().name(), null,
                    request.getNewMaturityDate() == null ? null : request.getNewMaturityDate().toString(),
                    request.getStatus() == LoanRescheduleStatus.INVESTOR_REJECTED ? ActorType.INVESTOR : ActorType.SYSTEM,
                    "SYSTEM", null));
        }
    }
}
