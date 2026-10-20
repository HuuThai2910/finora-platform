package com.finora.payment.repository.withdrawal;

import com.finora.payment.domain.withdrawal.PaymentWithdrawal;
import com.finora.payment.domain.withdrawal.WithdrawalStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentWithdrawalRepository extends JpaRepository<PaymentWithdrawal, Long> {

    Optional<PaymentWithdrawal> findByWithdrawalId(UUID withdrawalId);

    Optional<PaymentWithdrawal> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);

    /** Lịch sử của một chủ ví, mới nhất trước (index {@code ix_payment_withdrawals_owner_created}). */
    Page<PaymentWithdrawal> findByOwnerIdOrderByCreatedAtDescIdDesc(String ownerId, Pageable pageable);

    /**
     * Tổng đã rút trong ngày nghiệp vụ, không tính lệnh thất bại (đã hoàn). Gọi khi đã khóa dòng ví để hai lệnh đồng
     * thời không cùng thấy còn hạn mức (index {@code ix_payment_withdrawals_daily}).
     */
    @Query("""
            select coalesce(sum(w.amount), 0) from PaymentWithdrawal w
            where w.walletId = :walletId and w.businessDate = :businessDate
              and w.status <> com.finora.payment.domain.withdrawal.WithdrawalStatus.FAILED
            """)
    BigDecimal sumNotFailedOn(@Param("walletId") UUID walletId, @Param("businessDate") LocalDate businessDate);

    /** Hàng đợi worker theo lượt kế tiếp (index {@code ix_payment_withdrawals_due}). */
    @Query("""
            select w.id from PaymentWithdrawal w
            where w.status in :statuses and w.nextAttemptAt is not null and w.nextAttemptAt <= :now
            order by w.nextAttemptAt asc, w.id asc
            """)
    List<Long> findDueIds(@Param("statuses") Collection<WithdrawalStatus> statuses, @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PaymentWithdrawal w where w.id = :id")
    Optional<PaymentWithdrawal> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PaymentWithdrawal w where w.withdrawalId = :withdrawalId")
    Optional<PaymentWithdrawal> findByWithdrawalIdForUpdate(@Param("withdrawalId") UUID withdrawalId);

    Page<PaymentWithdrawal> findByStatusInOrderByUpdatedAtAscIdAsc(Collection<WithdrawalStatus> statuses,
            Pageable pageable);
}
