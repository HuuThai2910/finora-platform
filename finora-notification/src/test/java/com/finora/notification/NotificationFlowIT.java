package com.finora.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.notification.domain.DeferredEventStatus;
import com.finora.notification.domain.DevicePlatform;
import com.finora.notification.domain.DeviceRevocationReason;
import com.finora.notification.domain.InAppNotification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.ProcessedEvent;
import com.finora.notification.domain.PushDelivery;
import com.finora.notification.domain.PushDeliveryStatus;
import com.finora.notification.domain.PushDevice;
import com.finora.notification.domain.PushDeviceStatus;
import com.finora.notification.dto.request.UpdateNotificationPreferencesRequest;
import com.finora.notification.dto.response.PushDeviceResponse;
import com.finora.notification.integration.push.PushOutcome;
import com.finora.notification.integration.push.PushTicket;
import com.finora.notification.messaging.consumer.KafkaEventProcessor;
import com.finora.notification.repository.DeferredEventRepository;
import com.finora.notification.repository.DueScheduleRepository;
import com.finora.notification.repository.InAppNotificationRepository;
import com.finora.notification.repository.ProcessedEventRepository;
import com.finora.notification.repository.PushDeliveryRepository;
import com.finora.notification.repository.PushDeviceRepository;
import com.finora.notification.service.intake.DeferredEventWorker;
import com.finora.notification.service.intake.IntakeResult;
import com.finora.notification.service.operations.NotificationOperationsService;
import com.finora.notification.service.preference.NotificationPreferenceService;
import com.finora.notification.service.push.PushDeviceService;
import com.finora.notification.service.push.PushDispatchService;
import com.finora.notification.service.push.PushDispatchWorker;
import com.finora.notification.service.push.PushReceiptWorker;
import com.finora.notification.service.recipient.RecipientConflictException;
import com.finora.notification.service.reminder.DueReminderService;
import com.finora.notification.support.FakePushSender;
import com.finora.notification.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * R7 trên PostgreSQL 17 thật: intake idempotent theo eventId, bảng tra người nhận, event chờ người nhận, quá hạn theo
 * đợt/nhóm, push (preference, giờ nghỉ, event cũ, retry/DEAD, DeviceNotRegistered, SKIP LOCKED), vòng đời thiết bị, nhắc
 * kỳ đến hạn và nâng cấp migration. Listener Kafka và worker nền tắt; test gọi consumer/worker trực tiếp với đồng hồ
 * điều khiển được.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "finora.notification.workers-enabled=false",
        "finora.notification.due-reminder.enabled=false"
})
@Testcontainers
@Import(NotificationFlowIT.TestBeans.class)
class NotificationFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_notification_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    /** 10:00 sáng 17/10/2026 giờ Việt Nam: ngoài giờ nghỉ. */
    private static final Instant MORNING = Instant.parse("2026-10-17T03:00:00Z");
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicLong SEQUENCE = new AtomicLong(1000);

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(MORNING);
        }

        @Bean
        @Primary
        FakePushSender fakePushSender() {
            return new FakePushSender();
        }
    }

    @Autowired private KafkaEventProcessor processor;
    @Autowired private MutableClock clock;
    @Autowired private FakePushSender sender;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private InAppNotificationRepository notifications;
    @Autowired private PushDeliveryRepository deliveries;
    @Autowired private PushDeviceRepository devices;
    @Autowired private ProcessedEventRepository processed;
    @Autowired private DeferredEventRepository deferred;
    @Autowired private DueScheduleRepository schedules;
    @Autowired private PushDeviceService deviceService;
    @Autowired private NotificationPreferenceService preferenceService;
    @Autowired private PushDispatchWorker dispatchWorker;
    @Autowired private PushReceiptWorker receiptWorker;
    @Autowired private PushDispatchService dispatchService;
    @Autowired private DeferredEventWorker deferredWorker;
    @Autowired private DueReminderService reminders;
    @Autowired private NotificationOperationsService operations;
    @Autowired private Flyway flyway;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE notification_push_deliveries, notification_push_devices, in_app_notifications, "
                + "notification_preferences, notification_processed_events, notification_deferred_events, "
                + "notification_loan_lenders, notification_loan_references, notification_due_schedules CASCADE");
        clock.set(MORNING);
        sender.reset();
    }

    @Test
    void contextUsesPostgreSql17AndAllMigrationsApplied() {
        assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("17.");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
    }

    @Test
    void borrowerAndInvestorsFollowTheLoanFromFundingToDisbursement() {
        Long loanId = SEQUENCE.incrementAndGet();
        String borrower = "b-" + loanId;
        String lender = "i1-" + loanId;
        String lenderWithoutPhone = "i2-" + loanId;
        String borrowerToken = token("borrower" + loanId);
        String lenderToken = token("lender" + loanId);
        deviceService.register(borrower, borrowerToken, DevicePlatform.ANDROID, "1.0.0");
        deviceService.register(lender, lenderToken, DevicePlatform.IOS, "1.0.0");

        assertThat(fundingRequested(loanId, borrower)).isEqualTo(IntakeResult.DELIVERED);
        assertThat(fullyFunded(loanId, 1L, lender, lenderWithoutPhone)).isEqualTo(IntakeResult.DELIVERED);
        assertThat(send("finora.loan.investor-signature-requested", "InvestorSignatureRequested", 1, Map.of(
                "loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId,
                "contractNumber", "LC-2026-" + loanId, "allocationVersion", 1, "lenderCount", 2,
                "expiresAt", "2026-10-24T03:00:00Z"))).isEqualTo(IntakeResult.DELIVERED);
        assertThat(send("finora.loan.borrower-signature-requested", "BorrowerSignatureRequested", 1, Map.of(
                "loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId,
                "contractNumber", "LC-2026-" + loanId, "lenderCount", 2, "expiresAt", "2026-10-24T03:00:00Z")))
                .isEqualTo(IntakeResult.DELIVERED);
        assertThat(send("finora.loan.contract-activated", "LoanContractActivated", 1, Map.of(
                "loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId,
                "contractNumber", "LC-2026-" + loanId, "allocationVersion", 1,
                "activatedAt", "2026-10-17T03:00:00Z"))).isEqualTo(IntakeResult.DELIVERED);
        assertThat(disbursed(loanId)).isEqualTo(IntakeResult.DELIVERED);

        assertThat(types(borrower)).containsExactlyInAnyOrder("LOAN_FUNDING_OPENED", "LOAN_FULLY_FUNDED",
                "BORROWER_SIGNATURE_REQUESTED", "CONTRACT_ACTIVATED", "LOAN_DISBURSED");
        assertThat(types(lender)).containsExactlyInAnyOrder("INVESTOR_SIGNATURE_REQUESTED",
                "INVESTOR_CONTRACT_ACTIVATED", "NOTES_ISSUED");
        assertThat(types(lenderWithoutPhone)).hasSize(3);
        InAppNotification disbursedMessage = inbox(borrower).stream()
                .filter(value -> value.getType().equals("LOAN_DISBURSED")).findFirst().orElseThrow();
        assertThat(disbursedMessage.getMessage()).contains("10000000.00 VND").contains("LN-2026-" + loanId);
        assertThat(disbursedMessage.getBusinessReference()).isEqualTo(loanId.toString());
        assertThat(disbursedMessage.getCategory()).isEqualTo(NotificationCategory.DISBURSEMENT);

        // Push: mỗi thông báo một lần trên mỗi thiết bị đang hoạt động; nhà đầu tư không có máy chỉ có hộp thư.
        assertThat(pushesFor(borrower)).hasSize(5);
        assertThat(pushesFor(lender)).hasSize(3);
        assertThat(pushesFor(lenderWithoutPhone)).isEmpty();
        assertThat(dispatchWorker.runOnce()).isEqualTo(8);
        assertThat(sender.sent()).allSatisfy(message -> {
            assertThat(message.body()).doesNotContainPattern("\\d");
            assertThat(message.title()).doesNotContainPattern("\\d");
            assertThat(message.data()).containsOnlyKeys("notificationId", "type", "category");
        });
        assertThat(pushesFor(borrower)).extracting(PushDelivery::getStatus).containsOnly(PushDeliveryStatus.SENT);
    }

    @Test
    void sameEventIdDeliveredTwiceCreatesNothingTheSecondTime() {
        Long loanId = SEQUENCE.incrementAndGet();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = Map.of("loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId,
                "fundingRound", 1, "targetAmount", "10000000.00", "borrowerId", "b-" + loanId);

        assertThat(send(eventId, "finora.loan.funding-requested", "LoanFundingRequested", 1, data, MORNING))
                .isEqualTo(IntakeResult.DELIVERED);
        assertThat(send(eventId, "finora.loan.funding-requested", "LoanFundingRequested", 1, data, MORNING))
                .isEqualTo(IntakeResult.DUPLICATE);

        assertThat(inbox("b-" + loanId)).hasSize(1);
        assertThat(processed.count()).isEqualTo(1);
    }

    @Test
    void eventWaitsWhileItsRecipientsAreStillOnAnotherTopic() {
        Long loanId = SEQUENCE.incrementAndGet();
        String lender = "i-" + loanId;

        // Notification đọc dồn sau sự cố: lời mời ký tới trước LoanFullyFunded (nơi có danh sách nhà đầu tư).
        IntakeResult early = send("finora.loan.investor-signature-requested", "InvestorSignatureRequested", 1, Map.of(
                "loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId,
                "contractNumber", "LC-2026-" + loanId, "allocationVersion", 1, "lenderCount", 1,
                "expiresAt", "2026-10-24T03:00:00Z"));
        assertThat(early).isEqualTo(IntakeResult.DEFERRED);
        assertThat(deferred.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getWaitReason()).isEqualTo("LENDERS_UNKNOWN"));

        fundingRequested(loanId, "b-" + loanId);
        fullyFunded(loanId, 1L, lender);
        clock.advance(Duration.ofSeconds(31));
        assertThat(deferredWorker.runOnce()).isEqualTo(1);

        assertThat(types(lender)).containsExactly("INVESTOR_SIGNATURE_REQUESTED");
        assertThat(deferred.count()).isZero();
    }

    @Test
    void recipientsThatNeverArriveExpireIntoADeadStateForOperations() {
        Long loanId = SEQUENCE.incrementAndGet();
        assertThat(repaymentDistributed(loanId, LocalDate.parse("2026-11-17"), "8000000.00"))
                .isEqualTo(IntakeResult.DEFERRED);

        clock.advance(Duration.ofHours(25));
        deferredWorker.runOnce();

        assertThat(deferred.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(DeferredEventStatus.EXPIRED));
        assertThat(processed.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getOutcome()).isEqualTo(ProcessedEvent.Outcome.EXPIRED_PARTIAL));
        assertThat(operations.deferredEvents(DeferredEventStatus.EXPIRED, 0, 20).getTotalElements()).isEqualTo(1);
    }

    @Test
    void overdueNotifiesTheBorrowerOnlyWhenTheEpisodeOrStageChanges() {
        Long loanId = SEQUENCE.incrementAndGet();
        String borrower = "b-" + loanId;
        fundingRequested(loanId, borrower);

        List<IntakeResult> daily = new ArrayList<>();
        for (int day = 1; day <= 11; day++) {
            daily.add(delinquency(loanId, borrower, day - 1, day, day - 1 <= 9 ? 1 : 2, day <= 9 ? 1 : 2));
        }
        delinquency(loanId, borrower, 11, 0, 2, 1);

        assertThat(daily.stream().filter(result -> result == IntakeResult.FILTERED)).hasSize(9);
        assertThat(types(borrower)).containsExactlyInAnyOrder("LOAN_FUNDING_OPENED", "LOAN_OVERDUE",
                "OVERDUE_STAGE_CHANGED", "OVERDUE_CURED");
        InAppNotification stage = inbox(borrower).stream()
                .filter(value -> value.getType().equals("OVERDUE_STAGE_CHANGED")).findFirst().orElseThrow();
        assertThat(stage.getMessage()).contains("nhóm nợ 2").contains("cần chú ý");
    }

    @Test
    void investorDailyDelinquencyTicksStayOutOfTheInboxButMilestonesAndUnknownTypesAreDelivered() {
        Long loanId = SEQUENCE.incrementAndGet();
        String investor = "i-" + loanId;

        // B60: DPD tăng một ngày, Investment không đánh dấu mốc.
        assertThat(investorServicing(loanId, investor, "DELINQUENCY_CHANGED", 5, 1, false))
                .isEqualTo(IntakeResult.FILTERED);
        assertThat(investorServicing(loanId, investor, "BAD_DEBT_MILESTONE", 92, 3, false))
                .isEqualTo(IntakeResult.FILTERED);
        assertThat(investorServicing(loanId, investor, "DELINQUENCY_CHANGED", 10, 2, true))
                .isEqualTo(IntakeResult.DELIVERED);
        // B61: loại mới chưa biết không bị viết thành "đang quá hạn".
        assertThat(investorServicing(loanId, investor, "NOTE_CONVERSION_STARTED", 0, 1, false))
                .isEqualTo(IntakeResult.DELIVERED);

        List<InAppNotification> inbox = inbox(investor);
        assertThat(inbox).extracting(InAppNotification::getType)
                .containsExactlyInAnyOrder("DELINQUENCY_CHANGED", "NOTE_CONVERSION_STARTED");
        InAppNotification unknown = inbox.stream().filter(value -> value.getType().equals("NOTE_CONVERSION_STARTED"))
                .findFirst().orElseThrow();
        assertThat(unknown.getMessage()).doesNotContain("quá hạn").contains("cập nhật mới");
        assertThat(unknown.getCategory()).isEqualTo(NotificationCategory.GENERAL);
    }

    @Test
    void investorRepaymentCreditKeepsTheExistingContractForMobileAndE2e() {
        Long loanId = SEQUENCE.incrementAndGet();
        String investor = "i-" + loanId;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changeType", "REPAYMENT_CREDITED");
        data.put("loanApplicationId", loanId);
        data.put("loanNumber", null);
        data.put("daysPastDue", 0);
        data.put("debtGroup", 1);
        data.put("amount", "1726000.00");
        data.put("externalPushRequired", false);
        data.put("changedAt", "2026-10-17T03:00:00Z");
        data.put("recipients", List.of(
                Map.of("noteId", 1, "noteNumber", "NT-1", "investorId", investor, "amount", "600000.00"),
                Map.of("noteId", 2, "noteNumber", "NT-2", "investorId", investor, "amount", "242500.00")));

        send("finora.investment.note-servicing-changed", "InvestorNoteServicingChanged", 1, data);

        InAppNotification credit = inbox(investor).getFirst();
        assertThat(credit.getType()).isEqualTo("REPAYMENT_CREDITED");
        assertThat(credit.getBusinessReference()).isEqualTo(loanId.toString());
        assertThat(credit.getMessage()).contains("842500.00 VND").startsWith("Khoản vay #" + loanId);
        // Investment không đánh dấu push cho tiền trả nợ hằng kỳ.
        assertThat(credit.isExternalPushRequired()).isFalse();
    }

    @Test
    void conflictingBorrowerFailsClosedInsteadOfNotifyingSomeone() {
        Long loanId = SEQUENCE.incrementAndGet();
        fundingRequested(loanId, "b-" + loanId);

        assertThatThrownBy(() -> delinquency(loanId, "someone-else", 0, 1, 1, 1))
                .isInstanceOf(RecipientConflictException.class);
        assertThat(inbox("someone-else")).isEmpty();
    }

    @Test
    void pushIsSentConfirmedAndRevokedWhenExpoSaysDeviceNotRegistered() {
        Long loanId = SEQUENCE.incrementAndGet();
        String borrower = "b-" + loanId;
        String phone = token("phone" + loanId);
        deviceService.register(borrower, phone, DevicePlatform.ANDROID, "1.0.0");
        fundingRequested(loanId, borrower);

        dispatchWorker.runOnce();
        PushDelivery sent = pushesFor(borrower).getFirst();
        assertThat(sent.getStatus()).isEqualTo(PushDeliveryStatus.SENT);
        assertThat(sent.getProvider()).isEqualTo("LOG_MOCK");
        clock.advance(Duration.ofMinutes(16));
        receiptWorker.runOnce();
        assertThat(pushesFor(borrower).getFirst().getStatus()).isEqualTo(PushDeliveryStatus.CONFIRMED);

        sender.answer(phone, PushTicket.failed(PushOutcome.DEVICE_NOT_REGISTERED, "DeviceNotRegistered"));
        fullyFunded(loanId, 1L, "i-" + loanId);
        dispatchWorker.runOnce();

        PushDevice revoked = devices.findAll().getFirst();
        assertThat(revoked.getStatus()).isEqualTo(PushDeviceStatus.REVOKED);
        assertThat(revoked.getRevokedReason()).isEqualTo(DeviceRevocationReason.DEVICE_NOT_REGISTERED);
        assertThat(revoked.getToken()).isNull();
        assertThat(pushesFor(borrower)).extracting(PushDelivery::getStatus)
                .containsExactlyInAnyOrder(PushDeliveryStatus.CONFIRMED, PushDeliveryStatus.FAILED);
    }

    @Test
    void transientProviderFailuresRetryWithBackoffThenDeadLetterAndCanBeRequeued() {
        Long loanId = SEQUENCE.incrementAndGet();
        String borrower = "b-" + loanId;
        String phone = token("retry" + loanId);
        deviceService.register(borrower, phone, DevicePlatform.IOS, "1.0.0");
        sender.answer(phone, PushTicket.failed(PushOutcome.RETRYABLE_FAILURE, "EXPO_HTTP_503"));
        fundingRequested(loanId, borrower);

        dispatchWorker.runOnce();
        PushDelivery first = pushesFor(borrower).getFirst();
        assertThat(first.getStatus()).isEqualTo(PushDeliveryStatus.PENDING);
        assertThat(first.getNextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(1)));
        // Chưa tới lượt: worker không gửi lại sớm.
        assertThat(dispatchWorker.runOnce()).isZero();

        for (int attempt = 2; attempt <= 6; attempt++) {
            clock.advance(Duration.ofHours(2));
            assertThat(dispatchWorker.runOnce()).isEqualTo(1);
        }

        PushDelivery dead = pushesFor(borrower).getFirst();
        assertThat(dead.getStatus()).isEqualTo(PushDeliveryStatus.DEAD);
        assertThat(dead.getAttemptCount()).isEqualTo(6);
        assertThat(operations.pushDeliveries(PushDeliveryStatus.DEAD, 0, 20).getTotalElements()).isEqualTo(1);

        sender.reset();
        operations.requeue(dead.getId(), "admin-1");
        dispatchWorker.runOnce();
        assertThat(pushesFor(borrower).getFirst().getStatus()).isEqualTo(PushDeliveryStatus.SENT);
    }

    @Test
    void quietHoursDelayPushStaleEventsSkipPushAndOptOutKeepsOnlyTheInbox() {
        Long nightLoan = SEQUENCE.incrementAndGet();
        String nightBorrower = "b-" + nightLoan;
        deviceService.register(nightBorrower, token("night" + nightLoan), DevicePlatform.ANDROID, "1.0.0");
        // 23:30 giờ Việt Nam.
        clock.set(Instant.parse("2026-10-17T16:30:00Z"));
        fundingRequested(nightLoan, nightBorrower);
        assertThat(pushesFor(nightBorrower).getFirst().getNextAttemptAt())
                .isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        withdrawal(nightBorrower, "WithdrawalCompleted");
        assertThat(pushesFor(nightBorrower)).extracting(PushDelivery::getNextAttemptAt)
                .contains(clock.instant());

        clock.set(MORNING);
        Long staleLoan = SEQUENCE.incrementAndGet();
        String staleBorrower = "b-" + staleLoan;
        deviceService.register(staleBorrower, token("stale" + staleLoan), DevicePlatform.ANDROID, "1.0.0");
        send(UUID.randomUUID(), "finora.loan.funding-requested", "LoanFundingRequested", 1,
                Map.of("loanApplicationId", staleLoan, "applicationNumber", "LA-2026-" + staleLoan,
                        "fundingRound", 1, "targetAmount", "10000000.00", "borrowerId", staleBorrower),
                MORNING.minus(Duration.ofDays(2)));
        assertThat(inbox(staleBorrower)).hasSize(1);
        assertThat(pushesFor(staleBorrower)).isEmpty();

        Long quietLoan = SEQUENCE.incrementAndGet();
        String optedOut = "b-" + quietLoan;
        deviceService.register(optedOut, token("optout" + quietLoan), DevicePlatform.ANDROID, "1.0.0");
        preferenceService.update(optedOut, new UpdateNotificationPreferencesRequest(List.of(
                new UpdateNotificationPreferencesRequest.Item(NotificationCategory.FUNDING, false))));
        fundingRequested(quietLoan, optedOut);
        assertThat(inbox(optedOut)).hasSize(1);
        assertThat(pushesFor(optedOut)).isEmpty();
        assertThat(preferenceService.get(optedOut).categories())
                .filteredOn(item -> item.category() == NotificationCategory.FUNDING)
                .singleElement().satisfies(item -> assertThat(item.pushEnabled()).isFalse());
    }

    @Test
    void deviceRegistrationIsIdempotentMovesSharedPhonesAndCapsDevicesPerUser() {
        String shared = token("shared");
        PushDeviceResponse first = deviceService.register("u-1", shared, DevicePlatform.ANDROID, "1.0.0");
        PushDeviceResponse again = deviceService.register("u-1", shared, DevicePlatform.ANDROID, "1.0.1");
        assertThat(again.deviceId()).isEqualTo(first.deviceId());

        // Người khác đăng nhập trên cùng máy mà lần đăng xuất trước không gỡ được token.
        PushDeviceResponse moved = deviceService.register("u-2", shared, DevicePlatform.ANDROID, "1.0.1");
        PushDevice previous = devices.findById(first.deviceId()).orElseThrow();
        assertThat(previous.getStatus()).isEqualTo(PushDeviceStatus.REVOKED);
        assertThat(previous.getRevokedReason()).isEqualTo(DeviceRevocationReason.REASSIGNED);
        assertThat(previous.getToken()).isNull();
        assertThat(moved.deviceId()).isNotEqualTo(first.deviceId());

        List<UUID> registered = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            clock.advance(Duration.ofMinutes(1));
            registered.add(deviceService.register("u-3", token("cap" + index), DevicePlatform.IOS, "1.0.0")
                    .deviceId());
        }
        assertThat(devices.findById(registered.getFirst()).orElseThrow().getRevokedReason())
                .isEqualTo(DeviceRevocationReason.DEVICE_LIMIT);
        assertThat(devices.findAll().stream().filter(device -> device.getUserId().equals("u-3"))
                .filter(PushDevice::isActive)).hasSize(5);

        // Đăng xuất: người khác không gỡ được thiết bị của u-2; chính chủ gỡ được, push đang chờ bị bỏ qua.
        Long loanId = SEQUENCE.incrementAndGet();
        fundingRequested(loanId, "u-2");
        deviceService.unregister("u-9", moved.deviceId());
        assertThat(devices.findById(moved.deviceId()).orElseThrow().isActive()).isTrue();
        deviceService.unregister("u-2", moved.deviceId());
        assertThat(devices.findById(moved.deviceId()).orElseThrow().getRevokedReason())
                .isEqualTo(DeviceRevocationReason.LOGOUT);
        dispatchWorker.runOnce();
        assertThat(pushesFor("u-2")).extracting(PushDelivery::getStatus).containsOnly(PushDeliveryStatus.SKIPPED);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void concurrentDispatchWorkersClaimDisjointBatches() throws Exception {
        for (int index = 0; index < 30; index++) {
            Long loanId = SEQUENCE.incrementAndGet();
            deviceService.register("c-" + loanId, token("concurrent" + loanId), DevicePlatform.ANDROID, "1.0.0");
            fundingRequested(loanId, "c-" + loanId);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<PushDispatchService.ClaimedPush>>> claims = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return dispatchService.claimDue("LOG_MOCK");
                }));
            }
            start.countDown();
            Set<Long> seen = new HashSet<>();
            int total = 0;
            for (Future<List<PushDispatchService.ClaimedPush>> claim : claims) {
                for (PushDispatchService.ClaimedPush push : claim.get()) {
                    assertThat(seen.add(push.deliveryId())).as("delivery claimed twice").isTrue();
                    total++;
                }
            }
            assertThat(total).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void dueRemindersFollowTheCoreScheduleAndSkipInstallmentsPaidEarly() {
        LocalDate today = LocalDate.ofInstant(MORNING, VIETNAM);
        LocalDate dueDate = today.plusDays(3);
        Long remindedLoan = SEQUENCE.incrementAndGet();
        Long paidEarlyLoan = SEQUENCE.incrementAndGet();
        fundingRequested(remindedLoan, "b-" + remindedLoan);
        fundingRequested(paidEarlyLoan, "b-" + paidEarlyLoan);
        repaymentDistributed(remindedLoan, dueDate, "8274000.00");
        repaymentDistributed(paidEarlyLoan, dueDate, "8274000.00");
        UUID heldRepayment = UUID.randomUUID();
        // P5 (đề xuất): Payment báo khoản trả kỳ được giữ tới ngày đến hạn (D16).
        assertThat(send("finora.payment.repayment-collected", "RepaymentCollected", 1, Map.of(
                "repaymentId", heldRepayment.toString(), "loanApplicationId", paidEarlyLoan,
                "borrowerId", "b-" + paidEarlyLoan, "repaymentType", "SCHEDULED", "amount", "1726000.00",
                "currency", "VND", "dueDate", dueDate.toString(), "collectedAt", "2026-10-17T02:00:00Z")))
                .isEqualTo(IntakeResult.DELIVERED);

        assertThat(reminders.remind(today)).isEqualTo(1);
        assertThat(reminders.remind(today)).as("chạy lại trong ngày không tạo tin thứ hai").isZero();
        InAppNotification reminder = inbox("b-" + remindedLoan).stream()
                .filter(value -> value.getType().equals("INSTALLMENT_DUE_SOON")).findFirst().orElseThrow();
        assertThat(reminder.getMessage()).contains(dueDate.format(java.time.format.DateTimeFormatter
                .ofPattern("dd/MM/yyyy"))).contains("1726000.00 VND");
        assertThat(types("b-" + paidEarlyLoan)).contains("INSTALLMENT_PAYMENT_HELD")
                .doesNotContain("INSTALLMENT_DUE_SOON");

        // Người vay hủy khoản giữ: kỳ đó lại cần được nhắc.
        send("finora.payment.repayment-cancelled", "RepaymentCancelled", 1, Map.of(
                "repaymentId", heldRepayment.toString(), "loanApplicationId", paidEarlyLoan,
                "borrowerId", "b-" + paidEarlyLoan, "dueDate", dueDate.toString(),
                "cancelledAt", "2026-10-17T02:30:00Z"));
        assertThat(reminders.remind(today)).isEqualTo(1);
        assertThat(types("b-" + paidEarlyLoan)).contains("INSTALLMENT_DUE_SOON", "INSTALLMENT_PAYMENT_CANCELLED");

        assertThat(reminders.remind(dueDate)).as("đúng ngày đến hạn").isEqualTo(2);
        assertThat(types("b-" + remindedLoan)).contains("INSTALLMENT_DUE_TODAY");
    }

    @Test
    void settlementClosesTheReminderScheduleAndPaidOffRepaymentDoesNotPushTwice() {
        Long loanId = SEQUENCE.incrementAndGet();
        String borrower = "b-" + loanId;
        deviceService.register(borrower, token("settle" + loanId), DevicePlatform.ANDROID, "1.0.0");
        fundingRequested(loanId, borrower);
        repaymentDistributed(loanId, LocalDate.parse("2026-11-17"), "8274000.00");
        repaymentDistributed(loanId, null, "0.00");
        send("finora.loan.settled", "LoanSettled", 1, Map.of("loanApplicationId", loanId,
                "loanNumber", "LN-2026-" + loanId, "contractNumber", "LC-2026-" + loanId, "borrowerId", borrower,
                "settledAt", "2026-10-17T03:10:00Z"));

        assertThat(schedules.findById(loanId).orElseThrow().getStatus().name()).isEqualTo("CLOSED");
        assertThat(types(borrower)).contains("LOAN_SETTLED");
        List<InAppNotification> repayments = inbox(borrower).stream()
                .filter(value -> value.getType().equals("REPAYMENT_RECORDED")).toList();
        assertThat(repayments).hasSize(2);
        assertThat(repayments).filteredOn(value -> value.getMessage().contains("không còn kỳ nào"))
                .singleElement().satisfies(value -> assertThat(value.isExternalPushRequired()).isFalse());
        assertThat(reminders.remind(LocalDate.parse("2026-11-14"))).isZero();
    }

    @Test
    void legacyInboxRowsAreUpgradedByTheNewMigrations() {
        Flyway legacy = Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .schemas("legacy_upgrade").locations("classpath:db/migration").target("1").load();
        legacy.migrate();
        jdbc.update("""
                INSERT INTO legacy_upgrade.in_app_notifications (id, source_event_id, recipient_id, type, title, message,
                    business_reference, external_push_required, occurred_at, read_at, created_at)
                VALUES (gen_random_uuid(), gen_random_uuid(), '7', 'REPAYMENT_CREDITED', 't', 'm', '12', false,
                    now(), NULL, now()),
                       (gen_random_uuid(), gen_random_uuid(), '7', 'SOMETHING_OLD', 't', 'm', '12', false,
                    now(), now(), now())
                """);

        Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .schemas("legacy_upgrade").locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForList("SELECT type, category FROM legacy_upgrade.in_app_notifications ORDER BY type"))
                .extracting(row -> row.get("type") + "=" + row.get("category"))
                .containsExactly("REPAYMENT_CREDITED=REPAYMENT", "SOMETHING_OLD=GENERAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legacy_upgrade.in_app_notifications "
                + "WHERE updated_at IS NULL", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private IntakeResult fundingRequested(Long loanId, String borrower) {
        return send("finora.loan.funding-requested", "LoanFundingRequested", 1, Map.of(
                "loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId, "fundingRound", 1,
                "targetAmount", "10000000.00", "borrowerId", borrower));
    }

    private IntakeResult fullyFunded(Long loanId, long allocationVersion, String... investors) {
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (int index = 0; index < investors.length; index++) {
            allocations.add(Map.of("commitmentId", index + 1, "investorId", investors[index],
                    "amount", "5000000.00"));
        }
        return send("finora.investment.loan-fully-funded", "LoanFullyFunded", 2, Map.of(
                "loanApplicationId", loanId, "applicationNumber", "LA-2026-" + loanId, "fundingRound", 1,
                "fundedAmount", "10000000.00", "allocationVersion", allocationVersion, "allocations", allocations));
    }

    private IntakeResult disbursed(Long loanId) {
        return send("finora.loan.disbursed", "LoanDisbursed", 1, Map.of(
                "sagaId", UUID.randomUUID().toString(), "loanApplicationId", loanId,
                "applicationNumber", "LA-2026-" + loanId, "contractNumber", "LC-2026-" + loanId,
                "amount", "10000000.00", "currency", "VND", "disbursedAt", "2026-10-17T03:05:00Z",
                "loanNumber", "LN-2026-" + loanId, "maturityDate", "2027-04-17"));
    }

    private IntakeResult repaymentDistributed(Long loanId, LocalDate nextDueDate, String totalOutstanding) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("repaymentId", UUID.randomUUID().toString());
        data.put("repaymentType", "SCHEDULED");
        data.put("loanApplicationId", loanId);
        data.put("amount", "1726000.00");
        data.put("totalOutstanding", totalOutstanding);
        data.put("currency", "VND");
        data.put("transactionDate", "2026-10-17");
        data.put("nextDueDate", nextDueDate == null ? null : nextDueDate.toString());
        data.put("nextDueAmount", nextDueDate == null ? "0.00" : "1726000.00");
        data.put("completedAt", clock.instant().plusSeconds(SEQUENCE.incrementAndGet()).toString());
        return send("finora.payment.repayment-distributed", "RepaymentDistributed", 1, data);
    }

    private IntakeResult delinquency(Long loanId, String borrower, int previousDpd, int dpd, int previousGroup,
            int group) {
        return send("finora.loan.delinquency-changed", "LoanDelinquencyChanged", 1, Map.ofEntries(
                Map.entry("loanApplicationId", loanId), Map.entry("loanNumber", "LN-2026-" + loanId),
                Map.entry("borrowerId", borrower), Map.entry("fineractLoanId", 1),
                Map.entry("previousDaysPastDue", previousDpd), Map.entry("daysPastDue", dpd),
                Map.entry("previousDebtGroup", previousGroup), Map.entry("debtGroup", group),
                Map.entry("overdueAmount", dpd == 0 ? "0.00" : "1726000.00"),
                Map.entry("totalOutstanding", "8274000.00"), Map.entry("dataAsOf", "2026-10-17T03:00:00Z")));
    }

    private IntakeResult investorServicing(Long loanId, String investor, String changeType, int dpd, int group,
            boolean push) {
        return send("finora.investment.note-servicing-changed", "InvestorNoteServicingChanged", 1, Map.of(
                "changeType", changeType, "loanApplicationId", loanId, "loanNumber", "LN-2026-" + loanId,
                "daysPastDue", dpd, "debtGroup", group, "amount", "0.00", "externalPushRequired", push,
                "changedAt", "2026-10-17T03:00:00Z",
                "recipients", List.of(Map.of("noteId", 1, "noteNumber", "NT-1", "investorId", investor))));
    }

    private IntakeResult withdrawal(String owner, String type) {
        String topic = type.equals("WithdrawalCompleted") ? "finora.payment.withdrawal-completed"
                : "finora.payment.withdrawal-failed";
        return send(topic, type, 1, Map.of("withdrawalId", UUID.randomUUID().toString(), "ownerType", "BORROWER",
                "ownerId", owner, "amount", "500000.00", "currency", "VND", "bankCode", "VCB",
                "accountNumberMasked", "****6789", "status", "COMPLETED",
                "occurredAt", clock.instant().toString()));
    }

    private IntakeResult send(String topic, String type, int version, Map<String, Object> data) {
        return send(UUID.randomUUID(), topic, type, version, data, clock.instant());
    }

    private IntakeResult send(UUID eventId, String topic, String type, int version, Map<String, Object> data,
            Instant occurredAt) {
        try {
            String payload = mapper.writeValueAsString(Map.of("eventId", eventId.toString(),
                    "occurredAt", occurredAt.toString(), "version", version, "data", data));
            return processor.process("test", topic, 0, SEQUENCE.incrementAndGet(), payload, eventId.toString(), type,
                    Integer.toString(version));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<InAppNotification> inbox(String recipient) {
        return notifications.findAll().stream().filter(value -> value.getRecipientId().equals(recipient)).toList();
    }

    private List<String> types(String recipient) {
        return inbox(recipient).stream().map(InAppNotification::getType).toList();
    }

    private List<PushDelivery> pushesFor(String recipient) {
        return deliveries.findAll().stream().filter(value -> value.getRecipientId().equals(recipient)).toList();
    }

    private static String token(String seed) {
        String body = (seed + "-FAKE-test-token").replaceAll("[^A-Za-z0-9_-]", "");
        return "ExponentPushToken[" + body + "]";
    }
}
