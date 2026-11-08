package com.finora.payment.repository.bankaccount;

import com.finora.payment.domain.bankaccount.entity.PaymentBankLinkRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentBankLinkRequestRepository extends JpaRepository<PaymentBankLinkRequest, Long> {

    Optional<PaymentBankLinkRequest> findByOwnerIdAndIdempotencyKey(String ownerId, String idempotencyKey);

    /** Khóa yêu cầu khi xác nhận/gửi lại OTP: hai lần nhập mã đồng thời không cùng được tính là lần thử đầu. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaymentBankLinkRequest r where r.linkRequestId = :linkRequestId")
    Optional<PaymentBankLinkRequest> findByLinkRequestIdForUpdate(@Param("linkRequestId") UUID linkRequestId);
}
