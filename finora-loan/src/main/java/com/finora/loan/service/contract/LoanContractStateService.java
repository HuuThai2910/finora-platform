package com.finora.loan.service.contract;

import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.contract.enums.ConsentAction;
import com.finora.loan.dto.contract.request.DeclineLoanContractRequest;
import com.finora.loan.dto.contract.request.SignLoanContractRequest;
import com.finora.loan.integration.signature.contract.DigestSignatureCommand;
import com.finora.loan.integration.signature.contract.SignatureEvidence;
import com.finora.loan.integration.signature.contract.SignatureSubmissionStatus;
import com.finora.loan.integration.signature.contract.SigningCertificate;

import java.time.Instant;
import java.util.List;

/**
 * Các bước transaction của vòng đời hợp đồng phía người vay: ký (click-wrap, SmartCA, PAdES), từ chối, hết hạn và
 * đối soát idempotency; nhà đầu tư từ chối cũng đi qua đây vì đóng cả Contract.
 */
public interface LoanContractStateService {

    /**
     * Kiểm tra nhanh trước external call; transaction commit vẫn kiểm tra lại dưới pessimistic lock.
     * Cách tách này tránh giữ DB lock trong lúc chờ provider chữ ký.
     */
    SignaturePreparation prepareSignature(
            String contractNumber,
            SignLoanContractRequest request,
            String actorId
    );

    /** Consent và history cùng transaction; expiry được commit trước khi API trả lỗi hết hạn. */
    ContractConsentResult sign(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            SignatureEvidence evidence,
            String actorId
    );

    /** Lưu durable SmartCA request trước khi gọi mạng; retry cùng key nhận lại đúng transaction. */
    ContractConsentResult beginDigitalSignature(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            String providerTransactionId,
            String documentId,
            String actorId
    );

    /** Chữ ký raw không lưu DB; chỉ lưu evidence hash và sinh receipt sau transition thành công. */
    LoanContract completeDigitalSignature(
            String contractNumber,
            SignatureEvidence evidence,
            String documentId,
            String actorId
    );

    /** SmartCA từ chối: đưa Contract đang SIGNING về chờ người vay ký lại. */
    LoanContract resetRejectedDigitalSignature(String contractNumber, String actorId);

    /**
     * Borrower ký PAdES với provider cục bộ (MOCK): kiểm consent, nhúng chữ ký cuối của chuỗi, lập trang biên nhận,
     * history, outbox và kích hoạt Contract trong cùng transaction đang khóa Contract.
     */
    ContractConsentResult signPades(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            SigningCertificate certificate,
            String actorId
    );

    /** SmartCA: lưu lần ký PREPARED và chuyển Contract sang SIGNING trước khi gửi digest cho VNPT. */
    PadesBorrowerStart beginPadesDigitalSignature(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            SignLoanContractRequest request,
            SigningCertificate certificate,
            String actorId
    );

    /** Lần ký PAdES đang chờ của borrower, khớp đúng transaction SmartCA đã ghi trên Contract. */
    PendingPadesSignature pendingBorrowerSignature(String contractNumber);

    /**
     * Áp kết quả SmartCA cho chữ ký cuối của borrower. Hoàn tất thì nhúng CMS rồi đi tiếp như luồng ký cũ (receipt,
     * event, kích hoạt); bị từ chối/quá hạn/chữ ký sai thì hủy lần ký và trả Contract về chờ borrower ký lại.
     */
    PadesBorrowerOutcome applyPadesSubmission(
            String contractNumber,
            Long signatureId,
            SignatureSubmissionStatus status,
            String signatureValue,
            boolean sessionExpired
    );

    /** Phiên PDF đang chờ không có Contract SIGNING tương ứng (đã hết hạn/đóng) thì được hủy để giải phóng lượt ký. */
    void abandonOrphanedPadesSignature(String contractNumber);

    /** Decline là terminal transition riêng, không đổi ngược Application APPROVED. */
    ContractConsentResult decline(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            DeclineLoanContractRequest request,
            String actorId
    );

    /**
     * Nhà đầu tư từ chối trước khi phần vốn của họ có chữ ký (FUNDING-REOPEN-001). Hợp đồng không đóng: phần vốn của
     * nhà đầu tư này rời hợp đồng, bên đã ký giữ nguyên, Contract chuyển {@code PENDING_REFUNDING} và Loan phát
     * {@code LoanFundingReopened.v1} để Investment nhả tiền của phần rời đi và đưa khoản vay lại lên sàn.
     */
    ContractConsentResult declineByInvestor(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            DeclineLoanContractRequest request,
            String investorId
    );

    /**
     * Đợt gọi vốn bổ sung hết hạn (LoanFundingExpired): Contract đang {@code PENDING_REFUNDING} của hồ sơ chuyển
     * {@code EXPIRED}. Không phát bồi hoàn vì Investment đã tự hủy phần vốn theo nhánh hết hạn.
     */
    void closeRefundingOnFundingExpired(Long applicationId, Instant now);

    /**
     * Người vay rút hồ sơ khi khoản vay đang gọi vốn bổ sung: Contract {@code PENDING_REFUNDING} chuyển
     * {@code DECLINED}. Caller phát {@code LoanFundingCancelled(BORROWER_WITHDREW)} trong cùng transaction.
     */
    void closeRefundingOnBorrowerWithdrawal(Long applicationId, String borrowerId, Instant now);

    /** Mỗi Contract chạy một transaction ngắn; worker không giữ lock cho cả batch. */
    boolean expireOne(Long contractId);

    /** ID hợp đồng đang chờ ký đã quá hạn, tối đa {@code expiry-batch-size} dòng. */
    List<Long> dueIds();

    /**
     * Consent đã commit theo {@code Idempotency-Key}; {@code null} nếu chưa có, khác nội dung
     * thì 409 {@code IDEMPOTENCY_KEY_REUSED}.
     */
    ContractConsentResult findCommittedByKey(
            String contractNumber,
            String idempotencyKey,
            String requestHash,
            ConsentAction action,
            String actorId
    );

    /** Kết quả mở phiên ký PAdES của borrower; {@code command == null} khi request là bản lặp hoặc Contract vừa hết hạn. */
    record PadesBorrowerStart(ContractConsentResult result, Long signatureId, DigestSignatureCommand command) {
    }

    record PendingPadesSignature(
            Long signatureId,
            String transactionId,
            String documentId,
            DigestSignatureCommand command
    ) {
    }

    /** {@code invalidSignature}: provider trả chữ ký không khớp chứng thư, lần ký đã bị hủy và đã commit. */
    record PadesBorrowerOutcome(LoanContract contract, boolean invalidSignature) {
    }
}
