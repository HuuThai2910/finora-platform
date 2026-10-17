package com.finora.notification.config;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cấu hình R7 của Notification ({@code finora.notification.*}). Giá trị mặc định nằm ở đây để test và môi trường thiếu
 * cấu hình vẫn chạy an toàn; {@code application.yml} chỉ map biến môi trường.
 *
 * @param businessTimeZone múi giờ ngày nghiệp vụ (D7) cho nhắc lịch, giờ nghỉ và câu chữ ngày giờ
 */
@ConfigurationProperties(prefix = "finora.notification")
public record NotificationProperties(
        ZoneId businessTimeZone,
        Kafka kafka,
        Push push,
        Deferred deferred,
        DueReminder dueReminder
) {
    public NotificationProperties {
        businessTimeZone = businessTimeZone == null ? ZoneId.of("Asia/Ho_Chi_Minh") : businessTimeZone;
        kafka = kafka == null ? new Kafka(null, null, null, null, null) : kafka;
        push = push == null ? new Push(null, 0, 0, null, null, null, null, null, 0, 0, null, null, 0, null, null) : push;
        deferred = deferred == null ? new Deferred(null, null, null, 0) : deferred;
        dueReminder = dueReminder == null ? new DueReminder(false, null, 0) : dueReminder;
    }

    /**
     * @param domainGroup             consumer group của các event nghiệp vụ mới ({@code notification-domain-events})
     * @param investorServicingGroup  group cũ của {@code InvestorNoteServicingChanged}, giữ nguyên để không đọc lại offset
     * @param deadLetterSuffix        hậu tố DLT riêng của Notification: topic dùng chung với Investment/Payment nên DLT
     *                                phải tách theo consumer để không lẫn lỗi của service khác
     */
    public record Kafka(String domainGroup, String investorServicingGroup, String deadLetterSuffix,
            String noteServicingChangedTopic, Topics topics) {
        public Kafka {
            domainGroup = text(domainGroup, "notification-domain-events");
            investorServicingGroup = text(investorServicingGroup, "notification-investor-servicing");
            deadLetterSuffix = text(deadLetterSuffix, ".notification.DLT");
            noteServicingChangedTopic = text(noteServicingChangedTopic, "finora.investment.note-servicing-changed");
            topics = topics == null ? new Topics(null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null) : topics;
        }
    }

    /** Topic nguồn; tên mặc định theo {@code 05-registry.md} và contract Payment Wave 3 (rút tiền). */
    public record Topics(String loanFundingRequested, String loanFullyFunded, String loanFundingExpired,
            String loanFundingCancelled, String investorSignatureRequested, String borrowerSignatureRequested,
            String loanContractActivated, String loanDisbursed, String repaymentDistributed,
            String loanDelinquencyChanged, String loanSettled, String loanRescheduleConsentRequested,
            String loanRescheduleConsentCompleted, String loanRescheduled, String withdrawalCompleted,
            String withdrawalFailed, String repaymentCollected, String repaymentCancelled) {
        public Topics {
            loanFundingRequested = text(loanFundingRequested, "finora.loan.funding-requested");
            loanFullyFunded = text(loanFullyFunded, "finora.investment.loan-fully-funded");
            loanFundingExpired = text(loanFundingExpired, "finora.investment.loan-funding-expired");
            loanFundingCancelled = text(loanFundingCancelled, "finora.loan.funding-cancelled");
            investorSignatureRequested = text(investorSignatureRequested, "finora.loan.investor-signature-requested");
            borrowerSignatureRequested = text(borrowerSignatureRequested, "finora.loan.borrower-signature-requested");
            loanContractActivated = text(loanContractActivated, "finora.loan.contract-activated");
            loanDisbursed = text(loanDisbursed, "finora.loan.disbursed");
            repaymentDistributed = text(repaymentDistributed, "finora.payment.repayment-distributed");
            loanDelinquencyChanged = text(loanDelinquencyChanged, "finora.loan.delinquency-changed");
            loanSettled = text(loanSettled, "finora.loan.settled");
            loanRescheduleConsentRequested = text(loanRescheduleConsentRequested,
                    "finora.loan.reschedule-consent-requested");
            loanRescheduleConsentCompleted = text(loanRescheduleConsentCompleted,
                    "finora.investment.reschedule-consent-completed");
            loanRescheduled = text(loanRescheduled, "finora.loan.rescheduled");
            withdrawalCompleted = text(withdrawalCompleted, "finora.payment.withdrawal-completed");
            withdrawalFailed = text(withdrawalFailed, "finora.payment.withdrawal-failed");
            repaymentCollected = text(repaymentCollected, "finora.payment.repayment-collected");
            repaymentCancelled = text(repaymentCancelled, "finora.payment.repayment-cancelled");
        }
    }

    /**
     * Chính sách push (D62).
     *
     * @param maxAttempts      số lần gửi tối đa với lỗi tạm thời trước khi DEAD
     * @param retryBackoff     khoảng chờ sau lần lỗi đầu, nhân đôi mỗi lần, không quá {@code maximumBackoff}
     * @param sendingLease     thời gian một worker được giữ lượt gửi; quá hạn thì worker khác nhận lại
     * @param receiptDelay     chờ bao lâu sau khi Expo nhận mới hỏi receipt (Expo khuyến nghị khoảng 15 phút)
     * @param maxEventAge      event cũ hơn mức này (đọc dồn sau sự cố) chỉ vào hộp thư, không push
     * @param ttl              thời gian APNs/FCM còn cố giao push khi máy tắt; quá hạn thì bỏ, tránh push lỗi thời
     */
    public record Push(PushProviderType provider, int batchSize, int maxAttempts, Duration retryBackoff,
            Duration maximumBackoff, Duration sendingLease, Duration receiptDelay, Duration receiptRetryInterval,
            int receiptMaxChecks, int receiptBatchSize, Duration maxEventAge, Duration ttl, int maxDevicesPerUser,
            QuietHours quietHours, Expo expo) {
        public Push {
            provider = provider == null ? PushProviderType.LOG : provider;
            batchSize = batchSize == 0 ? 100 : batchSize;
            maxAttempts = maxAttempts == 0 ? 6 : maxAttempts;
            retryBackoff = retryBackoff == null ? Duration.ofMinutes(1) : retryBackoff;
            maximumBackoff = maximumBackoff == null ? Duration.ofHours(1) : maximumBackoff;
            sendingLease = sendingLease == null ? Duration.ofMinutes(2) : sendingLease;
            receiptDelay = receiptDelay == null ? Duration.ofMinutes(15) : receiptDelay;
            receiptRetryInterval = receiptRetryInterval == null ? Duration.ofHours(1) : receiptRetryInterval;
            receiptMaxChecks = receiptMaxChecks == 0 ? 6 : receiptMaxChecks;
            receiptBatchSize = receiptBatchSize == 0 ? 300 : receiptBatchSize;
            maxEventAge = maxEventAge == null ? Duration.ofHours(24) : maxEventAge;
            ttl = ttl == null ? Duration.ofHours(24) : ttl;
            maxDevicesPerUser = maxDevicesPerUser == 0 ? 5 : maxDevicesPerUser;
            quietHours = quietHours == null ? new QuietHours(true, null, null) : quietHours;
            expo = expo == null ? new Expo(null, null, null, null) : expo;
            // Expo nhận tối đa 100 thông điệp mỗi request gửi và 1000 ID mỗi request receipt.
            if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("push.batchSize phải từ 1 đến 100");
            if (receiptBatchSize < 1 || receiptBatchSize > 1000) {
                throw new IllegalArgumentException("push.receiptBatchSize phải từ 1 đến 1000");
            }
            if (maxAttempts < 1 || maxAttempts > 20) throw new IllegalArgumentException("push.maxAttempts phải từ 1 đến 20");
            if (maxDevicesPerUser < 1 || maxDevicesPerUser > 20) {
                throw new IllegalArgumentException("push.maxDevicesPerUser phải từ 1 đến 20");
            }
            if (maximumBackoff.compareTo(retryBackoff) < 0) {
                throw new IllegalArgumentException("push.maximumBackoff không được nhỏ hơn retryBackoff");
            }
        }
    }

    /** Giờ nghỉ theo {@link #businessTimeZone()}: push không gấp được dời tới {@code end} (D62). */
    public record QuietHours(boolean enabled, LocalTime start, LocalTime end) {
        public QuietHours {
            start = start == null ? LocalTime.of(22, 0) : start;
            end = end == null ? LocalTime.of(7, 0) : end;
        }
    }

    /**
     * @param accessToken Expo access token khi bật "enhanced push security"; secret, chỉ từ biến môi trường
     */
    public record Expo(String baseUrl, String accessToken, Duration connectTimeout, Duration readTimeout) {
        public Expo {
            baseUrl = text(baseUrl, "https://exp.host/--/api/v2/push");
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        }

        public boolean hasAccessToken() {
            return accessToken != null && !accessToken.isBlank();
        }

        /** Không để access token lọt vào log khi record bị in ra. */
        @Override
        public String toString() {
            return "Expo[baseUrl=" + baseUrl + ", accessToken=" + (hasAccessToken() ? "***" : "<none>")
                    + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
        }
    }

    /**
     * @param retryInterval lần thử lại đầu tiên; nhân đôi mỗi lần, không quá {@code maxInterval}
     * @param maxWait       chờ người nhận tối đa; quá hạn thì gửi cho người đã biết và chuyển EXPIRED
     */
    public record Deferred(Duration retryInterval, Duration maxInterval, Duration maxWait, int batchSize) {
        public Deferred {
            retryInterval = retryInterval == null ? Duration.ofSeconds(30) : retryInterval;
            maxInterval = maxInterval == null ? Duration.ofMinutes(30) : maxInterval;
            maxWait = maxWait == null ? Duration.ofHours(24) : maxWait;
            batchSize = batchSize == 0 ? 50 : batchSize;
        }
    }

    /**
     * Nhắc kỳ đến hạn (D64). Mặc định tắt cho tới khi Payment phát {@code RepaymentCollected}/{@code RepaymentCancelled}
     * (P5): thiếu hai event đó Notification không biết người vay đã trả trước kỳ (D16) và sẽ nhắc nhầm người đã trả.
     *
     * @param offsetsDays nhắc trước ngày đến hạn bao nhiêu ngày (0 là đúng ngày đến hạn)
     */
    public record DueReminder(boolean enabled, List<Integer> offsetsDays, int batchSize) {
        public DueReminder {
            offsetsDays = offsetsDays == null || offsetsDays.isEmpty() ? List.of(3, 0) : List.copyOf(offsetsDays);
            batchSize = batchSize == 0 ? 200 : batchSize;
            if (offsetsDays.stream().anyMatch(day -> day == null || day < 0 || day > 30)) {
                throw new IllegalArgumentException("due-reminder.offsetsDays phải từ 0 đến 30");
            }
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
