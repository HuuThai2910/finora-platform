package com.finora.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Một nhà đầu tư trong allocation đã khóa của khoản vay ({@code LoanFullyFunded.v2}). Chỉ dùng để báo các bước trước
 * khi Note có thể đổi chủ: mời ký, hợp đồng hiệu lực, hủy đợt gọi vốn, phát hành Note. Sau giải ngân, người nhận tin
 * servicing của nhà đầu tư là chủ Note hiện tại do Investment fan-out ({@code InvestorNoteServicingChanged}).
 */
@Entity
@Table(name = "notification_loan_lenders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanLender {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_application_id", nullable = false, updatable = false) private Long loanApplicationId;
    @Column(name = "investor_id", nullable = false, length = 100, updatable = false) private String investorId;
    @Column(name = "allocation_version", nullable = false, updatable = false) private long allocationVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static LoanLender create(Long loanApplicationId, String investorId, long allocationVersion, Instant now) {
        LoanLender value = new LoanLender();
        value.loanApplicationId = loanApplicationId;
        value.investorId = investorId;
        value.allocationVersion = allocationVersion;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }
}
