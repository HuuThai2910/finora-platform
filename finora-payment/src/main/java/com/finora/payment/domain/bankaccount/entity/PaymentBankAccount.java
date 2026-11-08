package com.finora.payment.domain.bankaccount.entity;

import com.finora.payment.domain.bankaccount.enums.BankAccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Tài khoản ngân hàng đã liên kết và xác thực OTP (BL-001) — nơi nhận duy nhất của lệnh rút từ app. Huỷ liên kết chỉ đổi
 * trạng thái sang {@code REMOVED}: lệnh rút cũ vẫn giữ bản sao thông tin tài khoản của riêng nó.
 */
@Entity
@Table(name = "payment_bank_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentBankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;
    @Column(name = "owner_id", nullable = false, updatable = false, length = 100)
    private String ownerId;
    @Column(name = "bank_code", nullable = false, updatable = false, length = 20)
    private String bankCode;
    @Column(name = "account_number_encrypted", nullable = false, updatable = false, length = 512)
    private String accountNumberEncrypted;
    @Column(name = "account_fingerprint", nullable = false, updatable = false, length = 64)
    private String accountFingerprint;
    @Column(name = "account_number_masked", nullable = false, updatable = false, length = 32)
    private String accountNumberMasked;
    @Column(name = "account_holder_name", nullable = false, updatable = false, length = 100)
    private String accountHolderName;
    @Column(name = "is_default", nullable = false)
    private boolean defaultAccount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BankAccountStatus status;
    @Column(name = "link_request_id", nullable = false, updatable = false)
    private UUID linkRequestId;
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;
    @Column(name = "removed_at")
    private Instant removedAt;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static PaymentBankAccount link(UUID bankAccountId, PaymentBankLinkRequest request, boolean defaultAccount,
            Instant now) {
        PaymentBankAccount value = new PaymentBankAccount();
        value.bankAccountId = Objects.requireNonNull(bankAccountId, "bankAccountId");
        value.ownerId = request.getOwnerId();
        value.bankCode = request.getBankCode();
        value.accountNumberEncrypted = request.getAccountNumberEncrypted();
        value.accountFingerprint = request.getAccountFingerprint();
        value.accountNumberMasked = request.getAccountNumberMasked();
        value.accountHolderName = request.getAccountHolderName();
        value.defaultAccount = defaultAccount;
        value.status = BankAccountStatus.ACTIVE;
        value.linkRequestId = request.getLinkRequestId();
        value.linkedAt = now;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public boolean isActive() {
        return status == BankAccountStatus.ACTIVE;
    }

    public void markDefault(boolean value, Instant now) {
        if (value && !isActive()) {
            throw new IllegalStateException("Tài khoản đã huỷ liên kết không thể làm mặc định");
        }
        defaultAccount = value;
        updatedAt = now;
    }

    public void remove(Instant now) {
        if (!isActive()) {
            throw new IllegalStateException("Tài khoản đã huỷ liên kết");
        }
        status = BankAccountStatus.REMOVED;
        defaultAccount = false;
        removedAt = now;
        updatedAt = now;
    }
}
