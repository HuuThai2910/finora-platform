package com.finora.payment.service.repayment;

import com.finora.payment.dto.response.RepaymentResponse;
import com.finora.payment.dto.response.ScheduledDueResponse;
import com.finora.payment.integration.fineract.FineractRepaymentException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Thu nợ phía Payment: trừ ví người vay cho lượt trả kỳ (kể cả khắc phục quá hạn), trả trước một phần và tất toán theo
 * báo giá; ghi khoản đã thu vào Fineract, đối soát khi chưa rõ kết quả core và chia tiền cho nhà đầu tư theo Note.
 */
public interface PaymentRepaymentService {

    /**
     * Trả kỳ của người dùng hiện tại: số tiền phải đúng nghĩa vụ Fineract đang báo, ngày trả là ngày nghiệp vụ hiện
     * tại. Idempotent theo {@code idempotencyKey}.
     */
    RepaymentResponse create(Long loanApplicationId, BigDecimal amount, LocalDate transactionDate,
            String idempotencyKey);

    /**
     * Người vay hủy khoản trả kỳ trước hạn đang giữ (D16) trước ngày ghi nhận, ví dụ để trả trước một phần hoặc tất
     * toán theo dư nợ hiện tại. Tiền về lại số dư khả dụng; từ ngày ghi nhận trở đi khoản này không hủy được nữa.
     */
    RepaymentResponse cancelPending(UUID repaymentId);

    /**
     * Tất toán theo báo giá đã lập: trừ ví đúng tổng báo giá, mỗi báo giá dùng một lần. Idempotent theo
     * {@code idempotencyKey}.
     */
    RepaymentResponse createEarlySettlement(UUID quoteId, String idempotencyKey);

    /**
     * Trả trước một phần theo báo giá đã lập: trừ ví đúng tổng báo giá, mỗi báo giá dùng một lần. Idempotent theo
     * {@code idempotencyKey}.
     */
    RepaymentResponse createPartialPrepayment(UUID quoteId, String idempotencyKey);

    /**
     * Đọc nghĩa vụ hiện tại từ Fineract cho đúng người vay sở hữu khoản vay, không trừ tiền và không ghi gì. Mobile
     * dùng số này thay cho projection để lệnh trả nợ không bị từ chối vì lệch nghĩa vụ.
     */
    ScheduledDueResponse scheduledDue(Long loanApplicationId);

    /** Khoản trả nợ theo mã; khoản của người khác trả {@code 403 REPAYMENT_OWNER_MISMATCH}. */
    RepaymentResponse get(UUID repaymentId);

    /** Khoản đã thu tới lượt ghi Fineract; khoản trả kỳ trước hạn chỉ tới lượt từ ngày đến hạn. */
    List<Long> collectedIds();

    /** Khoản Fineract đã ghi, chờ chia tiền cho nhà đầu tư. */
    List<Long> corePostedIds();

    /** Khoản cần đối soát với Fineract. */
    List<Long> reconciliationRequiredIds();

    /** Khoản còn ở bước ghi Fineract từ trước {@code cutoff}: worker có thể đã chết giữa chừng. */
    List<Long> staleCorePostingIds(Instant cutoff);

    /** Khoản ghi Fineract bị treo chuyển sang đối soát; chỉ được tra cứu theo externalId, không POST lại. */
    void recoverStaleCorePosting(Long id);

    /**
     * Ghi khoản đã thu vào Fineract ngoài transaction: Fineract từ chối chắc chắn thì hoàn tiền về ví, kết quả chưa rõ
     * thì chuyển đối soát.
     */
    void postToCore(Long id);

    /**
     * Đối soát chỉ đọc: tra Fineract theo externalId, có giao dịch thì ghi nhận kết quả; không POST lại, không hoàn
     * tiền.
     */
    void reconcileCorePosting(Long id);

    /**
     * B32 (D28): admin ghi lại khoản trả nợ RECONCILIATION_REQUIRED mà Fineract chưa có. Thứ tự: tra cứu theo
     * externalId (có thì chỉ ghi nhận kết quả) → nhận việc → POST lại với đúng externalId, số tiền và ngày ghi nhận ban
     * đầu. Fineract từ chối (4xx) thì tra cứu thêm một lần trước khi hoàn tiền, vì lỗi có thể là trùng externalId với
     * giao dịch đã ghi; không tra được thì giữ đối soát, không hoàn tiền khi kết quả core chưa chắc chắn.
     *
     * @throws FineractRepaymentException khi chưa đọc được Fineract ở bước tra cứu đầu; khoản trả nợ không đổi
     */
    void repostCorePosting(Long id, String actorId, Duration safeAfter);

    /**
     * Chia khoản Fineract đã ghi: gốc, lãi và phần phí trả trước của nhà đầu tư theo dư nợ Note, phần còn lại cho
     * FINORA; phát {@code RepaymentDistributed}.
     */
    void distribute(Long id);
}
