package com.finora.payment.repository.bankaccount;

import com.finora.payment.domain.bankaccount.entity.PaymentBankAccount;
import com.finora.payment.domain.bankaccount.enums.BankAccountStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentBankAccountRepository extends JpaRepository<PaymentBankAccount, Long> {

    Optional<PaymentBankAccount> findByBankAccountId(UUID bankAccountId);

    /** Tài khoản đang dùng của một người, mặc định trước rồi mới nhất trước (index {@code ix_payment_bank_accounts_owner}). */
    @Query("""
            select a from PaymentBankAccount a
            where a.ownerId = :ownerId and a.status = com.finora.payment.domain.bankaccount.enums.BankAccountStatus.ACTIVE
            order by a.defaultAccount desc, a.linkedAt desc, a.id desc
            """)
    List<PaymentBankAccount> findActiveByOwner(@Param("ownerId") String ownerId);

    /**
     * Như {@link #findActiveByOwner} nhưng khóa các dòng: liên kết, đổi mặc định và huỷ liên kết của cùng một người chạy
     * tuần tự, nên giới hạn số tài khoản và "đúng một tài khoản mặc định" không bị hai request đồng thời phá.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from PaymentBankAccount a
            where a.ownerId = :ownerId and a.status = com.finora.payment.domain.bankaccount.enums.BankAccountStatus.ACTIVE
            order by a.linkedAt desc, a.id desc
            """)
    List<PaymentBankAccount> findActiveByOwnerForUpdate(@Param("ownerId") String ownerId);

    boolean existsByOwnerIdAndBankCodeAndAccountFingerprintAndStatus(String ownerId, String bankCode,
            String accountFingerprint, BankAccountStatus status);
}
