package com.finora.investment.service.messaging;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.messaging.ProcessedEvent;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.restructure.InvestorRescheduleConsent;
import com.finora.investment.messaging.event.InvestorServicingNotificationEventData;
import com.finora.investment.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.InvestorRescheduleConsentRepository;
import com.finora.investment.repository.ProcessedEventRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshot chủ Note hiện tại; chuyển nhượng sau thời điểm này không thay phiếu đã phát hành.
 *
 * <p>P7 (W3F, D78): lúc mở phiếu, cùng transaction ghi outbox {@code InvestorNoteServicingChanged.v1}
 * {@code changeType = RESCHEDULE_CONSENT_REQUESTED} mời biểu quyết, mỗi chủ phiếu đúng một người nhận, có push. Chỉ phát
 * khi phiếu được tạo lần đầu cho {@code requestId}: Loan phát lại đề nghị (eventId mới) không mời lần hai; Kafka giao
 * trùng cùng eventId đã bị {@code processed_events} chặn. Event không mang PII hay số dư của từng phiếu.</p>
 */
@Service
@RequiredArgsConstructor
public class LoanRescheduleConsentRequestedHandler {
    static final String INVITATION_CHANGE_TYPE = "RESCHEDULE_CONSENT_REQUESTED";

    private final ProcessedEventRepository processed;
    private final InvestmentNoteRepository notes;
    private final InvestorRescheduleConsentRepository consents;
    private final InvestmentOutboxService outbox;
    private final Clock clock;

    @Transactional
    public void handle(UUID eventId, Instant occurredAt, LoanRescheduleConsentRequestedEventData data) {
        if (processed.existsByEventId(eventId)) return;
        validate(data);
        Instant now = clock.instant();
        if (!consents.existsByRequestId(data.requestId())) {
            Map<String, BigDecimal> principalByInvestor = new LinkedHashMap<>();
            for (InvestmentNote note : notes.findByLoanIdAndStatusIn(data.loanApplicationId(),
                    List.of(NoteStatus.ACTIVE, NoteStatus.DEFAULTED))) {
                if (note.getOutstandingPrincipal().signum() > 0) {
                    principalByInvestor.merge(note.getInvestorId(), note.getOutstandingPrincipal(), BigDecimal::add);
                }
            }
            if (principalByInvestor.isEmpty()) {
                throw new IllegalArgumentException("Khoản vay không có chủ Note còn dư nợ để lấy đồng ý cơ cấu");
            }
            consents.saveAll(principalByInvestor.entrySet().stream().map(entry ->
                    InvestorRescheduleConsent.pending(data.requestId(), data.loanApplicationId(),
                            data.loanNumber(), data.requestType(), data.rescheduleFromDate(),
                            data.adjustedDueDate(), data.extraTerms(), data.originalMaturityDate(),
                            entry.getKey(), entry.getValue(), now)).toList());
            inviteBallotHolders(data, principalByInvestor.keySet());
        }
        processed.save(ProcessedEvent.create(eventId, "LoanRescheduleConsentRequested", 1,
                "finora-loan", data.requestId().toString(), occurredAt, now));
    }

    /**
     * Một phiếu gộp mọi Note của nhà đầu tư trên khoản vay nên người nhận không gắn với một Note ({@code noteId},
     * {@code noteNumber}, {@code amount} để trống). {@code daysPastDue}/{@code debtGroup}/{@code amount} giữ giá trị trung
     * tính như {@code RESCHEDULED}; {@code maturityDate} là ngày đáo hạn hiện tại (lịch cũ vẫn hiệu lực khi biểu quyết).
     * Key outbox theo khoản vay như các event servicing khác để giữ thứ tự.
     */
    private void inviteBallotHolders(LoanRescheduleConsentRequestedEventData data, Collection<String> investorIds) {
        List<InvestorServicingNotificationEventData.Recipient> recipients = investorIds.stream()
                .map(investorId -> new InvestorServicingNotificationEventData.Recipient(null, null, investorId, null))
                .toList();
        outbox.record("LOAN", data.loanApplicationId().toString(), "InvestorNoteServicingChanged", 1,
                new InvestorServicingNotificationEventData(INVITATION_CHANGE_TYPE, data.loanApplicationId(),
                        data.loanNumber(), 0, 1, "0.00", data.originalMaturityDate(), true, data.requestedAt(),
                        recipients));
    }

    private static void validate(LoanRescheduleConsentRequestedEventData data) {
        if (data == null || data.requestId() == null || data.loanApplicationId() == null
                || data.loanNumber() == null || data.loanNumber().isBlank()
                || data.requestType() == null || data.rescheduleFromDate() == null
                || data.requestedAt() == null) {
            throw new IllegalArgumentException("Đề nghị đồng ý cơ cấu thiếu trường bắt buộc");
        }
        boolean installment = "INSTALLMENT_ADJUSTMENT".equals(data.requestType())
                && data.adjustedDueDate() != null && data.extraTerms() == null;
        boolean extension = "TERM_EXTENSION".equals(data.requestType())
                && data.adjustedDueDate() == null && data.extraTerms() != null && data.extraTerms() > 0;
        if (!installment && !extension) throw new IllegalArgumentException("Hình dạng đề nghị cơ cấu không hợp lệ");
    }
}
