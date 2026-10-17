package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bảng tra người nhận và nhãn hiển thị của một khoản vay, do Notification tự dựng từ event (D60, B64).
 * <p>
 * Loan là SoR; Notification chỉ giữ ID logic người vay (claim {@code user_id}) và các mã công khai để viết câu thông
 * báo. Người vay của một hồ sơ không bao giờ đổi, nên event nào cũng chỉ được điền chỗ trống: event mang người vay khác
 * với dòng đã có bị từ chối (fail closed) vì gửi nhầm người là lộ thông tin khoản vay. Dựng lại bằng cách đọc lại topic
 * từ đầu trong thời gian Kafka còn giữ; về lâu dài producer nên mang {@code borrowerId} trong event (P1–P4 của plan W3F).
 */
@Entity
@Table(name = "notification_loan_references")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanReference implements org.springframework.data.domain.Persistable<Long> {

    @Id @Column(name = "loan_application_id") private Long loanApplicationId;
    @Column(name = "borrower_id", length = 100) private String borrowerId;
    @Column(name = "application_number", length = 64) private String applicationNumber;
    @Column(name = "contract_number", length = 64) private String contractNumber;
    @Column(name = "loan_number", length = 64) private String loanNumber;
    /** Phiên bản allocation của danh sách nhà đầu tư đang lưu ({@code LoanFullyFunded.allocationVersion}). */
    @Column(name = "lender_allocation_version") private Long lenderAllocationVersion;
    @Column(name = "last_source_event_id", nullable = false) private UUID lastSourceEventId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    /** Khóa là ID khoản vay của Loan (gán sẵn), nên tự báo bản ghi mới để Spring Data INSERT thay vì merge. */
    @Transient private boolean isNew;

    public static LoanReference create(Long loanApplicationId, UUID sourceEventId, Instant now) {
        LoanReference value = new LoanReference();
        value.loanApplicationId = loanApplicationId;
        value.lastSourceEventId = sourceEventId;
        value.createdAt = now;
        value.updatedAt = now;
        value.isNew = true;
        return value;
    }

    @Override
    public Long getId() {
        return loanApplicationId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }

    /**
     * Điền người vay nếu chưa có. Trả {@code false} khi event báo một người vay khác: dữ liệu hai service mâu thuẫn,
     * caller phải dừng xử lý thay vì gửi cho ai đó.
     */
    public boolean acceptBorrower(String candidate) {
        if (candidate == null || candidate.isBlank()) return true;
        if (borrowerId == null) {
            borrowerId = candidate;
            return true;
        }
        return borrowerId.equals(candidate);
    }

    /** Mã công khai chỉ được điền khi còn trống; giá trị khác không ghi đè để câu thông báo cũ và mới nhất quán. */
    public void fillLabels(String applicationNumber, String contractNumber, String loanNumber) {
        if (this.applicationNumber == null && hasText(applicationNumber)) this.applicationNumber = applicationNumber;
        if (this.contractNumber == null && hasText(contractNumber)) this.contractNumber = contractNumber;
        if (this.loanNumber == null && hasText(loanNumber)) this.loanNumber = loanNumber;
    }

    public void lendersReplaced(long allocationVersion) {
        lenderAllocationVersion = allocationVersion;
    }

    public void touched(UUID sourceEventId, Instant now) {
        lastSourceEventId = sourceEventId;
        updatedAt = now;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
