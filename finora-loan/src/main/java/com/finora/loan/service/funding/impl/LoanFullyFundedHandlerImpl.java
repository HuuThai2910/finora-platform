package com.finora.loan.service.funding.impl;

import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.domain.core.entity.ScheduleCalculationSnapshot;
import com.finora.loan.domain.messaging.entity.ProcessedEvent;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.integration.profile.client.BorrowerIdentityClient;
import com.finora.loan.messaging.event.FundingAllocationEventData;
import com.finora.loan.messaging.event.LoanFullyFundedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.contract.FundedContractAllocation;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.service.funding.LoanFullyFundedHandler;
import com.finora.loan.util.HashingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Xử lý event {@code LoanFullyFunded}: khóa funding snapshot, lập một Contract chung và ghi marker cùng commit.
 *
 * <p>Allocation phải được sắp theo commitmentId và khớp {@code allocationHash} trước khi lập hợp đồng.</p>
 *
 * <p>Hợp đồng in họ tên + CCCD đã che của bên vay đọc từ finora-user. Lời gọi HTTP đó chạy trước transaction (không giữ
 * khóa hồ sơ trong lúc chờ mạng); phần ghi vẫn là một transaction như trước qua {@link TransactionTemplate}.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanFullyFundedHandlerImpl implements LoanFullyFundedHandler {

    private static final String EVENT_TYPE = "LoanFullyFunded";
    private static final int EVENT_VERSION = 2;
    private static final String SYSTEM_ACTOR = "SYSTEM-INVESTMENT-EVENT";

    private final ProcessedEventRepository processedEventRepository;
    private final LoanApplicationRepository loanApplicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleCalculationSnapshotRepository;
    private final LoanContractCreationService loanContractCreationService;
    private final LoanContractRepository loanContractRepository;
    private final BorrowerIdentityClient borrowerIdentityClient;
    private final LoanContractProperties loanContractProperties;
    private final HashingService hashingService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public void handle(UUID eventId, Instant occurredAt, LoanFullyFundedEventData eventData) {
        BorrowerIdentity borrowerIdentity = borrowerIdentityForContract(eventId, eventData);
        transactionTemplate.executeWithoutResult(status -> apply(eventId, occurredAt, eventData, borrowerIdentity));
    }

    /**
     * Đọc danh tính bên vay khi event này có thể lập hợp đồng mới hoặc phát hành lại hợp đồng chưa có danh tính. Event
     * đã xử lý, hồ sơ không tồn tại hay hợp đồng đã có danh tính thì không gọi User; transaction phía sau tự báo lỗi hoặc
     * bỏ qua như trước. Trạng thái đổi giữa lúc đọc và lúc ghi thì {@code createFunded} ném lỗi tạm thời và event được
     * thử lại.
     */
    private BorrowerIdentity borrowerIdentityForContract(UUID eventId, LoanFullyFundedEventData eventData) {
        if (eventData == null || eventData.loanApplicationId() == null
                || processedEventRepository.existsByEventId(eventId)) {
            return null;
        }
        LoanApplication application = loanApplicationRepository.findById(eventData.loanApplicationId()).orElse(null);
        if (application == null) {
            return null;
        }
        LoanContract contract = loanContractRepository.findByApplicationId(application.getId()).orElse(null);
        if (contract != null && (!contract.isPendingRefunding() || contract.getBorrowerIdentity() != null)) {
            return null;
        }
        return borrowerIdentityClient.identity(application.getBorrowerId());
    }

    private void apply(
            UUID eventId,
            Instant occurredAt,
            LoanFullyFundedEventData eventData,
            BorrowerIdentity borrowerIdentity
    ) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        validate(eventData);
        LoanApplication application = loanApplicationRepository.findByIdForUpdate(eventData.loanApplicationId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND_FOR_FUNDING",
                        "Không tìm thấy hồ sơ ứng với kết quả gọi vốn"));
        requireMatchingApplication(application, eventData);
        List<FundingAllocationEventData> ordered = eventData.allocations().stream()
                .sorted(Comparator.comparing(FundingAllocationEventData::commitmentId))
                .toList();
        if (!ordered.equals(eventData.allocations())) {
            throw new IllegalArgumentException("Allocation phải được sắp theo commitmentId");
        }
        String snapshotJson = hashingService.toJson(ordered);
        if (!hashingService.sha256Text(snapshotJson).equals(eventData.allocationHash())) {
            throw new IllegalArgumentException("allocationHash không khớp snapshot");
        }
        List<FundedContractAllocation> allocations = ordered.stream()
                .map(allocation -> new FundedContractAllocation(
                        allocation.commitmentId(), requireText(allocation.investorId(), "investorId"),
                        decimal(allocation.amount(), "amount"),
                        decimal(allocation.sharePercent(), "sharePercent"),
                        requireText(allocation.paymentHoldReference(), "paymentHoldReference")))
                .toList();
        Instant now = clock.instant();
        boolean newlyFunded = application.markFullyFunded(
                eventData.listingId(), decimal(eventData.fundedAmount(), "fundedAmount"),
                eventData.allocationVersion(), eventData.allocationHash(), snapshotJson,
                eventData.fundingCompletedAt(), SYSTEM_ACTOR, now);
        loanApplicationRepository.saveAndFlush(application);
        if (newlyFunded) {
            Long finalScheduleId = application.getFinalCalculationSnapshotId();
            if (finalScheduleId == null) {
                throw LoanBusinessException.conflict(
                        "FINAL_SCHEDULE_NOT_FOUND_FOR_CONTRACT",
                        "Hồ sơ chưa có lịch trả nợ cuối để lập hợp đồng");
            }
            ScheduleCalculationSnapshot schedule = scheduleCalculationSnapshotRepository
                    .findById(finalScheduleId)
                    .orElseThrow(() -> LoanBusinessException.conflict(
                            "FINAL_SCHEDULE_NOT_FOUND_FOR_CONTRACT",
                            "Không tìm thấy lịch trả nợ cuối để lập hợp đồng"));
            loanContractCreationService.createFunded(
                    application, schedule, allocations, eventData.allocationVersion(), eventData.allocationHash(),
                    now.plus(loanContractProperties.signatureWindow()), SYSTEM_ACTOR, borrowerIdentity, now);
        }
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, EVENT_TYPE, EVENT_VERSION, "finora-investment",
                eventData.applicationNumber(), occurredAt, now));
    }

    private void validate(LoanFullyFundedEventData eventData) {
        if (eventData == null || eventData.loanApplicationId() == null || eventData.listingId() == null
                || eventData.applicationNumber() == null || eventData.fundingRound() == null
                || eventData.allocationVersion() == null || eventData.allocationVersion() < 1
                || eventData.fundingCompletedAt() == null || eventData.allocations() == null
                || eventData.allocations().isEmpty() || !"VND".equals(eventData.currency())
                || eventData.allocationHash() == null
                || !eventData.allocationHash().matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("LoanFullyFunded thiếu hoặc sai trường bắt buộc");
        }
        HashSet<Long> commitmentIds = new HashSet<>();
        for (FundingAllocationEventData allocation : eventData.allocations()) {
            if (allocation == null || allocation.commitmentId() == null
                    || allocation.paymentHoldReference() == null
                    || allocation.paymentHoldReference().isBlank()
                    || !commitmentIds.add(allocation.commitmentId())) {
                throw new IllegalArgumentException("Allocation commitment bị thiếu hoặc trùng");
            }
        }
    }

    private void requireMatchingApplication(LoanApplication application, LoanFullyFundedEventData eventData) {
        if (!application.getApplicationNumber().equals(eventData.applicationNumber())
                || application.getFundingRound() == null
                || !application.getFundingRound().equals(eventData.fundingRound())) {
            throw LoanBusinessException.conflict(
                    "FUNDING_EVENT_APPLICATION_MISMATCH",
                    "Kết quả gọi vốn không khớp hồ sơ hoặc funding round");
        }
    }

    private BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(requireText(value, field));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " không phải decimal hợp lệ", exception);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được để trống");
        }
        return value.trim();
    }
}
