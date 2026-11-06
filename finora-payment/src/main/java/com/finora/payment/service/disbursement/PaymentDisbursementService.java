package com.finora.payment.service.disbursement;

import com.finora.payment.domain.disbursement.entity.PaymentDisbursement;
import com.finora.payment.integration.provider.client.PaymentProvider;
import com.finora.payment.messaging.event.DisbursementRequestedEventData;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Saga giải ngân phía Payment: nhận yêu cầu giải ngân từ Loan, giữ chỗ capture các khoản tiền nhà đầu tư đã giữ, gọi
 * provider chuyển tiền ngoài transaction, đối soát theo sagaId khi chưa rõ kết quả và ghi có ví người vay; kết quả báo
 * lại qua outbox ({@code DisbursementCompleted}/{@code DisbursementFailed}).
 */
public interface PaymentDisbursementService {

    /**
     * Nhận {@code DisbursementRequested}: lưu lệnh mới, hoặc kiểm lệnh đã có cùng sagaId phải trùng nội dung. Mỗi
     * {@code eventId} chỉ xử lý một lần.
     */
    void request(UUID eventId, DisbursementRequestedEventData data);

    /**
     * Lệnh cần xử lý: mới/chờ retry, đang gọi provider nhưng đã hết lease (worker chết), hoặc đến lượt tra cứu đối
     * soát. Truy vấn sắp theo {@code next_attempt_at, id} nên bản ghi lỗi lặp lại không chặn các bản ghi khác.
     */
    List<Long> dueIds();

    /** Lệnh đã giải ngân xong nhưng chưa có bút toán ghi có ví người vay. */
    List<Long> completedAwaitingBorrowerCreditIds();

    /**
     * Khóa lệnh và quyết định bước kế tiếp. Lệnh mới/chờ retry được giữ chỗ capture và nhận lease rồi mới gọi provider
     * ngoài transaction. Lệnh còn {@code PROCESSING} sau lease chuyển sang đối soát: không biết tiền đã chuyển hay chưa
     * nên tuyệt đối không gọi {@code disburse} lại.
     */
    Work claim(Long id);

    /** Một bước của worker: nhận việc, gọi provider (hoặc tra cứu khi đối soát) ngoài transaction rồi lưu kết quả. */
    void execute(Long id);

    /**
     * Áp kết quả tra cứu provider cho lệnh đang đối soát; chưa có kết quả thì hẹn lần tra cứu sau, hết lượt thì để vận
     * hành xử lý.
     */
    void applyReconciliation(Long id, Optional<PaymentProvider.PaymentProviderResult> found);

    /**
     * Áp kết quả provider cho lệnh: thành công thì capture khoản giữ, ghi có ví người vay và phát
     * {@code DisbursementCompleted}; thất bại thì hẹn thử lại, chuyển đối soát hoặc phát {@code DisbursementFailed}.
     * Kết quả đến sau khi lệnh đã chốt bị bỏ qua.
     */
    void finish(Long id, PaymentProvider.PaymentProviderResult result);

    /**
     * Bổ sung bút toán cho dữ liệu đã giải ngân trước khi FINORA nối ví người vay.
     * Không sửa ledger cũ; lệnh mới dùng khóa theo saga nên gọi lại không cộng tiền lần hai.
     */
    void reconcileBorrowerCredit(Long id);

    /** {@code reconcile = true}: không gọi chuyển tiền, chỉ tra cứu kết quả lần gọi trước theo sagaId. */
    record Work(Long id, UUID sagaId, String borrowerId, BigDecimal amount, String currency, boolean reconcile) {

        public static Work reconcile(PaymentDisbursement disbursement) {
            return new Work(disbursement.getId(), disbursement.getSagaId(), disbursement.getBorrowerId(),
                    disbursement.getAmount(), disbursement.getCurrency(), true);
        }
    }
}
