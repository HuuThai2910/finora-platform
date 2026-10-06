package com.finora.payment.repository.repayment;

import com.finora.payment.domain.repayment.PaymentRepayment;
import com.finora.payment.domain.repayment.PaymentRepaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepaymentRepository extends JpaRepository<PaymentRepayment, Long> {
    Optional<PaymentRepayment> findByRepaymentId(UUID repaymentId);
    Optional<PaymentRepayment> findByIdempotencyKey(String idempotencyKey);

    Page<PaymentRepayment> findByStatusInOrderByUpdatedAtAscIdAsc(
            List<PaymentRepaymentStatus> statuses, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select repayment from PaymentRepayment repayment where repayment.id = :id")
    Optional<PaymentRepayment> findByIdForUpdate(@Param("id") Long id);

    @Query("select repayment.id from PaymentRepayment repayment where repayment.status = :status order by repayment.createdAt, repayment.id")
    List<Long> findIdsByStatus(@Param("status") PaymentRepaymentStatus status, Pageable pageable);

    @Query("""
            select repayment.id from PaymentRepayment repayment
            where repayment.status = :status and repayment.updatedAt < :cutoff
            order by repayment.updatedAt, repayment.id
            """)
    List<Long> findIdsByStatusBefore(@Param("status") PaymentRepaymentStatus status,
            @Param("cutoff") Instant cutoff, Pageable pageable);
}
