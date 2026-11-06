package com.finora.notification.service.reminder.impl;

import com.finora.notification.config.NotificationProperties;
import com.finora.notification.domain.entity.DueSchedule;
import com.finora.notification.domain.entity.LoanReference;
import com.finora.notification.domain.enums.DueScheduleStatus;
import com.finora.notification.repository.DueScheduleRepository;
import com.finora.notification.repository.LoanReferenceRepository;
import com.finora.notification.service.delivery.NotificationDeliveryService;
import com.finora.notification.service.delivery.NotificationDraft;
import com.finora.notification.service.reminder.DueReminderService;
import com.finora.notification.service.template.NotificationTemplate;
import com.finora.notification.service.template.NotificationText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nhắc kỳ đến hạn (D64): trước {@code offsetsDays} ngày (mặc định 3 ngày và đúng ngày đến hạn) theo ngày nghiệp vụ Việt
 * Nam. Không nhắc kỳ mà người vay đã trả trước và Payment đang giữ (D16).
 * <p>
 * Idempotent: mỗi lời nhắc có {@code sourceEventId} cố định = UUID tên-hóa từ {@code (khoản vay, ngày đến hạn, số ngày
 * trước)}, nên chạy lại trong ngày, nhiều instance hay restart đều không tạo tin thứ hai (unique hộp thư).
 * Mỗi lô một transaction, đọc lô theo keyset; nhãn khoản vay đọc một câu cho cả lô (không N+1).
 */
@Slf4j
@Service
public class DueReminderServiceImpl implements DueReminderService {

    private final DueScheduleRepository dueScheduleRepository;
    private final LoanReferenceRepository loanReferenceRepository;
    private final NotificationDeliveryService notificationDeliveryService;
    private final NotificationText notificationText;
    private final TransactionTemplate transactionTemplate;
    private final NotificationProperties.DueReminder policy;
    private final Clock clock;

    public DueReminderServiceImpl(DueScheduleRepository dueScheduleRepository,
            LoanReferenceRepository loanReferenceRepository, NotificationDeliveryService notificationDeliveryService,
            NotificationText notificationText, TransactionTemplate transactionTemplate,
            NotificationProperties properties, Clock clock) {
        this.dueScheduleRepository = dueScheduleRepository;
        this.loanReferenceRepository = loanReferenceRepository;
        this.notificationDeliveryService = notificationDeliveryService;
        this.notificationText = notificationText;
        this.transactionTemplate = transactionTemplate;
        this.policy = properties.dueReminder();
        this.clock = clock;
    }

    @Override
    public int remind(LocalDate today) {
        int created = 0;
        for (int offset : policy.offsetsDays()) {
            LocalDate dueDate = today.plusDays(offset);
            NotificationTemplate template = offset == 0
                    ? NotificationTemplate.INSTALLMENT_DUE_TODAY : NotificationTemplate.INSTALLMENT_DUE_SOON;
            long after = 0;
            while (true) {
                long cursor = after;
                BatchResult batch = transactionTemplate.execute(
                        status -> remindBatch(dueDate, offset, template, cursor));
                if (batch == null || batch.size() == 0) break;
                created += batch.created();
                after = batch.lastLoanId();
                if (batch.size() < policy.batchSize()) break;
            }
        }
        if (created > 0) log.info("Đã tạo lời nhắc kỳ đến hạn: date={}, created={}", today, created);
        return created;
    }

    private BatchResult remindBatch(LocalDate dueDate, int offset, NotificationTemplate template, long after) {
        List<DueSchedule> batch = dueScheduleRepository.findDueBatch(DueScheduleStatus.ACTIVE, dueDate, after,
                PageRequest.of(0, policy.batchSize()));
        if (batch.isEmpty()) return new BatchResult(0, 0, after);
        Map<Long, LoanReference> labels = loanReferenceRepository.findAllById(batch.stream()
                        .map(DueSchedule::getLoanApplicationId).toList()).stream()
                .collect(Collectors.toMap(LoanReference::getLoanApplicationId, Function.identity()));
        Instant now = clock.instant();
        int created = 0;
        for (DueSchedule schedule : batch) {
            if (schedule.isPaidEarlyFor(dueDate)) continue;
            Long loanId = schedule.getLoanApplicationId();
            String amountClause = schedule.getNextDueAmount() == null ? ""
                    : ", số tiền " + notificationText.money(schedule.getNextDueAmount()) + " VND";
            NotificationDraft draft = NotificationDraft.of(schedule.getBorrowerId(), template, Map.of(
                    "loan", notificationText.loanLabel(loanId, labels.get(loanId), null, null, null),
                    "dueDate", notificationText.date(dueDate),
                    "amountClause", amountClause), loanId.toString());
            created += notificationDeliveryService.deliver(reminderId(loanId, dueDate, offset), now, List.of(draft));
        }
        return new BatchResult(batch.size(), created, batch.get(batch.size() - 1).getLoanApplicationId());
    }

    static UUID reminderId(Long loanApplicationId, LocalDate dueDate, int offset) {
        return UUID.nameUUIDFromBytes(("DUE_REMINDER:" + loanApplicationId + ":" + dueDate + ":" + offset)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record BatchResult(int size, int created, long lastLoanId) {}
}
