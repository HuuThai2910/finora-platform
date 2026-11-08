package com.finora.payment.domain.bankaccount.entity;

import com.finora.payment.domain.bankaccount.enums.BankLinkRequestStatus;
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
 * Một lần liên kết tài khoản ngân hàng đang chờ OTP (BL-001). Ngân hàng đã xác nhận chủ tài khoản khớp tên eKYC; người
 * dùng phải nhập đúng mã ngân hàng gửi trước khi hết hạn. Chỉ lưu dấu HMAC của mã OTP, không lưu mã gốc.
 * <p>
 * Vòng đời: {@code PENDING → CONFIRMED | LOCKED | EXPIRED}, chỉ đi tới.
 */
@Entity
@Table(name = "payment_bank_link_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentBankLinkRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "link_request_id", nullable = false, updatable = false)
    private UUID linkRequestId;
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
    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;
    @Column(name = "otp_expires_at", nullable = false)
    private Instant otpExpiresAt;
    @Column(name = "otp_sent_at", nullable = false)
    private Instant otpSentAt;
    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;
    @Column(name = "resend_count", nullable = false)
    private int resendCount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BankLinkRequestStatus status;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 150)
    private String idempotencyKey;
    @Version
    @Column(nullable = false)
    private Long version;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Tài khoản ngân hàng đã chuẩn hóa: số tài khoản chỉ ở dạng mã hóa, dấu vân tay và dạng che. */
    public record Account(String bankCode, String accountNumberEncrypted, String accountFingerprint,
            String accountNumberMasked, String accountHolderName) {
    }

    public static PaymentBankLinkRequest start(UUID linkRequestId, String ownerId, Account account, String otpHash,
            Instant otpExpiresAt, String idempotencyKey, Instant now) {
        PaymentBankLinkRequest value = new PaymentBankLinkRequest();
        value.linkRequestId = Objects.requireNonNull(linkRequestId, "linkRequestId");
        value.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        value.bankCode = account.bankCode();
        value.accountNumberEncrypted = account.accountNumberEncrypted();
        value.accountFingerprint = account.accountFingerprint();
        value.accountNumberMasked = account.accountNumberMasked();
        value.accountHolderName = account.accountHolderName();
        value.otpHash = Objects.requireNonNull(otpHash, "otpHash");
        value.otpExpiresAt = Objects.requireNonNull(otpExpiresAt, "otpExpiresAt");
        value.otpSentAt = now;
        value.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        value.status = BankLinkRequestStatus.PENDING;
        value.createdAt = now;
        value.updatedAt = now;
        return value;
    }

    public Account account() {
        return new Account(bankCode, accountNumberEncrypted, accountFingerprint, accountNumberMasked, accountHolderName);
    }

    public boolean isExpired(Instant now) {
        return !otpExpiresAt.isAfter(now);
    }

    /** Hết hạn khi người dùng quay lại nhập mã: đóng yêu cầu để không còn nhận OTP nào. */
    public void expire(Instant now) {
        requirePending();
        status = BankLinkRequestStatus.EXPIRED;
        updatedAt = now;
    }

    /** Nhập sai một lần; sai đủ {@code maxAttempts} lần thì khóa yêu cầu. Trả về số lần còn được thử. */
    public int failAttempt(int maxAttempts, Instant now) {
        requirePending();
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            status = BankLinkRequestStatus.LOCKED;
        }
        updatedAt = now;
        return Math.max(0, maxAttempts - failedAttempts);
    }

    public void confirm(Instant now) {
        requirePending();
        status = BankLinkRequestStatus.CONFIRMED;
        updatedAt = now;
    }

    /** Ngân hàng gửi mã mới: mã cũ hết hiệu lực, số lần nhập sai đếm lại từ đầu. */
    public void resend(String newOtpHash, Instant newExpiresAt, Instant now) {
        requirePending();
        otpHash = Objects.requireNonNull(newOtpHash, "newOtpHash");
        otpExpiresAt = Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        otpSentAt = now;
        failedAttempts = 0;
        resendCount++;
        updatedAt = now;
    }

    private void requirePending() {
        if (status != BankLinkRequestStatus.PENDING) {
            throw new IllegalStateException("Yêu cầu liên kết không còn chờ OTP: " + status);
        }
    }
}
