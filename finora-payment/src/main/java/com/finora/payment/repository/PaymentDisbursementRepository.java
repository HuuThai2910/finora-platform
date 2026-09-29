package com.finora.payment.repository;

import com.finora.payment.domain.disbursement.PaymentDisbursement;
import com.finora.payment.domain.disbursement.PaymentDisbursementStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentDisbursementRepository extends JpaRepository<PaymentDisbursement,Long>{
    Optional<PaymentDisbursement> findBySagaId(UUID sagaId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select d from PaymentDisbursement d where d.id=:id")
    Optional<PaymentDisbursement> findByIdForUpdate(@Param("id") Long id);
    @Query("select d.id from PaymentDisbursement d where d.status in :statuses and d.nextAttemptAt<=:now order by d.nextAttemptAt,d.id")
    List<Long> findDueIds(@Param("statuses") List<PaymentDisbursementStatus> statuses,@Param("now") Instant now, Pageable page);

    /**
     * Tìm các giao dịch legacy đã capture tiền nhà đầu tư vào clearing nhưng chưa ghi có ví người vay.
     * Khóa idempotency của ledger giúp worker sửa đúng một lần mà không sửa/xóa bút toán cũ.
     */
    @Query(value = """
            select d.id
            from payment_disbursements d
            where d.status = 'COMPLETED'
              and not exists (
                select 1
                from payment_ledger_transactions t
                where t.idempotency_key = 'BORROWER_CREDIT:' || cast(d.saga_id as text)
              )
            order by d.id
            """, nativeQuery = true)
    List<Long> findCompletedAwaitingBorrowerCreditIds(Pageable page);
}
