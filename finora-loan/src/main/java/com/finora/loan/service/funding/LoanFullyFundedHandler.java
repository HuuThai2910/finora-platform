package com.finora.loan.service.funding;

import com.finora.loan.config.LoanContractProperties;
import com.finora.loan.domain.application.LoanApplication;
import com.finora.loan.domain.core.ScheduleCalculationSnapshot;
import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.messaging.event.FundingAllocationEventData;
import com.finora.loan.messaging.event.LoanFullyFundedEventData;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.core.ScheduleCalculationSnapshotRepository;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.service.contract.FundedContractAllocation;
import com.finora.loan.service.contract.LoanContractCreationService;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consumer transaction: khóa funding snapshot, lập một Contract chung và ghi marker cùng commit. */
@Service
@RequiredArgsConstructor
public class LoanFullyFundedHandler {

    private static final String EVENT_TYPE = "LoanFullyFunded";
    private static final int EVENT_VERSION = 2;
    private static final String SYSTEM_ACTOR = "SYSTEM-INVESTMENT-EVENT";

    private final ProcessedEventRepository processedEventRepository;
    private final LoanApplicationRepository applicationRepository;
    private final ScheduleCalculationSnapshotRepository scheduleRepository;
    private final LoanContractCreationService contractCreationService;
    private final LoanContractProperties contractProperties;
    private final HashingService hashingService;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanFullyFundedEventData data) {
        if (processedEventRepository.existsByEventId(eventId)) {
            return;
        }
        validate(data);
        LoanApplication application = applicationRepository.findByIdForUpdate(data.loanApplicationId())
                .orElseThrow(() -> LoanBusinessException.conflict(
                        "LOAN_APPLICATION_NOT_FOUND_FOR_FUNDING",
                        "Không tìm thấy hồ sơ ứng với kết quả gọi vốn"));
        requireMatchingApplication(application, data);
        List<FundingAllocationEventData> ordered = data.allocations().stream()
                .sorted(Comparator.comparing(FundingAllocationEventData::commitmentId))
                .toList();
        if (!ordered.equals(data.allocations())) {
            throw new IllegalArgumentException("Allocation phải được sắp theo commitmentId");
        }
        String snapshotJson = hashingService.toJson(ordered);
        if (!hashingService.sha256Text(snapshotJson).equals(data.allocationHash())) {
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
                data.listingId(), decimal(data.fundedAmount(), "fundedAmount"),
                data.allocationVersion(), data.allocationHash(), snapshotJson,
                data.fundingCompletedAt(), SYSTEM_ACTOR, now);
        applicationRepository.saveAndFlush(application);
        if (newlyFunded) {
            Long finalScheduleId = application.getFinalCalculationSnapshotId();
            if (finalScheduleId == null) {
                throw LoanBusinessException.conflict(
                        "FINAL_SCHEDULE_NOT_FOUND_FOR_CONTRACT",
                        "Hồ sơ chưa có lịch trả nợ cuối để lập hợp đồng");
            }
            ScheduleCalculationSnapshot schedule = scheduleRepository
                    .findById(finalScheduleId)
                    .orElseThrow(() -> LoanBusinessException.conflict(
                            "FINAL_SCHEDULE_NOT_FOUND_FOR_CONTRACT",
                            "Không tìm thấy lịch trả nợ cuối để lập hợp đồng"));
            contractCreationService.createFunded(
                    application, schedule, allocations, data.allocationVersion(), data.allocationHash(),
                    now.plus(contractProperties.signatureWindow()), SYSTEM_ACTOR, now);
        }
        processedEventRepository.saveAndFlush(ProcessedEvent.create(
                eventId, EVENT_TYPE, EVENT_VERSION, "finora-investment",
                data.applicationNumber(), occurredAt, now));
    }

    private void validate(LoanFullyFundedEventData data) {
        if (data == null || data.loanApplicationId() == null || data.listingId() == null
                || data.applicationNumber() == null || data.fundingRound() == null
                || data.allocationVersion() == null || data.allocationVersion() < 1
                || data.fundingCompletedAt() == null || data.allocations() == null
                || data.allocations().isEmpty() || !"VND".equals(data.currency())
                || data.allocationHash() == null
                || !data.allocationHash().matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("LoanFullyFunded thiếu hoặc sai trường bắt buộc");
        }
        HashSet<Long> commitmentIds = new HashSet<>();
        for (FundingAllocationEventData allocation : data.allocations()) {
            if (allocation == null || allocation.commitmentId() == null
                    || allocation.paymentHoldReference() == null
                    || allocation.paymentHoldReference().isBlank()
                    || !commitmentIds.add(allocation.commitmentId())) {
                throw new IllegalArgumentException("Allocation commitment bị thiếu hoặc trùng");
            }
        }
    }

    private void requireMatchingApplication(LoanApplication application, LoanFullyFundedEventData data) {
        if (!application.getApplicationNumber().equals(data.applicationNumber())
                || application.getFundingRound() == null
                || !application.getFundingRound().equals(data.fundingRound())) {
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
