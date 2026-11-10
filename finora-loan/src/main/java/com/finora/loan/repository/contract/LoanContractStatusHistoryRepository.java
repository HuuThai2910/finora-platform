package com.finora.loan.repository.contract;

import com.finora.loan.domain.contract.entity.LoanContractStatusHistory;
import com.finora.loan.domain.contract.enums.LoanContractStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface LoanContractStatusHistoryRepository extends JpaRepository<LoanContractStatusHistory, Long> {

    Page<LoanContractStatusHistory> findByContractId(Long contractId, Pageable pageable);

    /**
     * Các lần phát hành văn bản, mới nhất trước: dòng lập hợp đồng ({@code fromStatus} null) và dòng phát hành lại sau
     * gọi vốn bổ sung ({@code reissuedFrom} → {@code reissuedTo}, tức PENDING_REFUNDING → PENDING_LENDER_SIGNATURES).
     * Chuyển trạng thái khác (bắt đầu ký, ký lỗi quay lại, hết hạn khi đang gọi vốn bổ sung) không phải phát hành nên
     * không tính. Mỗi hợp đồng chỉ vài dòng.
     */
    @Query("""
            select h.occurredAt from LoanContractStatusHistory h
            where h.contractId = :contractId
              and (h.fromStatus is null or (h.fromStatus = :reissuedFrom and h.toStatus = :reissuedTo))
            order by h.occurredAt desc
            """)
    List<Instant> findIssueTimes(@Param("contractId") Long contractId,
            @Param("reissuedFrom") LoanContractStatus reissuedFrom,
            @Param("reissuedTo") LoanContractStatus reissuedTo);
}
