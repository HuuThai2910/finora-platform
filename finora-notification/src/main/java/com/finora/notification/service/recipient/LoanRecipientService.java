package com.finora.notification.service.recipient;

import com.finora.notification.domain.LoanLender;
import com.finora.notification.domain.LoanReference;
import com.finora.notification.repository.LoanLenderRepository;
import com.finora.notification.repository.LoanReferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bảng tra người nhận theo khoản vay (D60, B64). Ghi trong transaction của intake: cùng commit với thông báo dùng nó.
 * Event mang người vay khác với dữ liệu đã có thì ném {@link RecipientConflictException} (vào DLT): gửi nhầm người là lộ
 * thông tin khoản vay, nên không bao giờ tự chọn một bên.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanRecipientService {

    private final LoanReferenceRepository references;
    private final LoanLenderRepository lenders;
    private final Clock clock;

    /** Ghi người vay và mã công khai từ event; trả dòng tham chiếu sau khi cập nhật. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LoanReference remember(Long loanApplicationId, String borrowerId, String applicationNumber,
            String contractNumber, String loanNumber, UUID sourceEventId) {
        requireLoanId(loanApplicationId);
        Instant now = clock.instant();
        LoanReference reference = references.findForUpdate(loanApplicationId)
                .orElseGet(() -> references.save(LoanReference.create(loanApplicationId, sourceEventId, now)));
        if (!reference.acceptBorrower(borrowerId)) {
            log.error("Người vay trong event khác bảng tra: loanApplicationId={}, eventId={}",
                    loanApplicationId, sourceEventId);
            throw new RecipientConflictException("Người vay của khoản vay " + loanApplicationId
                    + " không khớp giữa các event");
        }
        reference.fillLabels(applicationNumber, contractNumber, loanNumber);
        reference.touched(sourceEventId, now);
        return reference;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LoanReference> find(Long loanApplicationId) {
        return references.findById(loanApplicationId);
    }

    /**
     * Người vay để gửi: ưu tiên {@code borrowerId} trong event (khi producer đã bổ sung, P1–P4), kiểm khớp bảng tra;
     * không có thì lấy từ bảng tra.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<String> borrower(Long loanApplicationId, String borrowerFromEvent, String applicationNumber,
            String contractNumber, String loanNumber, UUID sourceEventId) {
        if (borrowerFromEvent != null && !borrowerFromEvent.isBlank()) {
            return Optional.ofNullable(remember(loanApplicationId, borrowerFromEvent, applicationNumber,
                    contractNumber, loanNumber, sourceEventId).getBorrowerId());
        }
        Optional<LoanReference> reference = references.findForUpdate(loanApplicationId);
        reference.ifPresent(value -> {
            value.fillLabels(applicationNumber, contractNumber, loanNumber);
            value.touched(sourceEventId, clock.instant());
        });
        return reference.map(LoanReference::getBorrowerId);
    }

    /**
     * Thay danh sách nhà đầu tư theo allocation mới (đợt gọi vốn mới sau khi hủy). Allocation cũ hơn hoặc trùng thì bỏ
     * qua, nên event đến muộn/trùng không đổi danh sách hiện hành.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceLenders(Long loanApplicationId, long allocationVersion, Collection<String> investorIds,
            String applicationNumber, UUID sourceEventId) {
        LoanReference reference = remember(loanApplicationId, null, applicationNumber, null, null, sourceEventId);
        Long current = reference.getLenderAllocationVersion();
        if (current != null && current >= allocationVersion) return;
        Instant now = clock.instant();
        lenders.deleteByLoan(loanApplicationId);
        List<LoanLender> rows = new LinkedHashSet<>(investorIds).stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> LoanLender.create(loanApplicationId, id, allocationVersion, now))
                .toList();
        lenders.saveAll(rows);
        reference.lendersReplaced(allocationVersion);
        reference.touched(sourceEventId, now);
    }

    /** Nhà đầu tư của allocation đang lưu; rỗng khi chưa nhận {@code LoanFullyFunded}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Lenders lenders(Long loanApplicationId) {
        Long version = references.findById(loanApplicationId).map(LoanReference::getLenderAllocationVersion)
                .orElse(null);
        List<String> ids = lenders.findByLoanApplicationIdOrderByInvestorId(loanApplicationId).stream()
                .map(LoanLender::getInvestorId).toList();
        return new Lenders(version, ids);
    }

    /** Danh sách nhà đầu tư và allocation version tương ứng. */
    public record Lenders(Long allocationVersion, List<String> investorIds) {
        public boolean known() {
            return allocationVersion != null && !investorIds.isEmpty();
        }

        /** Event mang allocation version: chỉ dùng danh sách khi đúng version đó. */
        public boolean matches(Long expectedVersion) {
            return known() && (expectedVersion == null || expectedVersion.equals(allocationVersion));
        }

        /** Event thuộc allocation cũ hơn danh sách đang lưu (đợt gọi vốn trước đã bị hủy): không còn ai cần báo. */
        public boolean supersedes(Long expectedVersion) {
            return known() && expectedVersion != null && allocationVersion > expectedVersion;
        }
    }

    private static void requireLoanId(Long loanApplicationId) {
        if (loanApplicationId == null || loanApplicationId <= 0) {
            throw new IllegalArgumentException("Event thiếu loanApplicationId");
        }
    }
}
