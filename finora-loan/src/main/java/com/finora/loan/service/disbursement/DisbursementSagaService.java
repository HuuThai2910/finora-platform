package com.finora.loan.service.disbursement;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.contract.entity.ContractParty;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.integration.fineract.client.CoreLoanBookingGateway;
import com.finora.loan.messaging.event.DisbursementCompletedEventData;
import com.finora.loan.messaging.event.DisbursementFailedEventData;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Disbursement Saga: mở saga khi hợp đồng có hiệu lực, nhận kết quả chuyển tiền của Payment, ghi khoản vay vào core
 * và phát {@code LoanDisbursed}.
 */
public interface DisbursementSagaService {

    /**
     * Mở saga chờ Payment và ghi outbox {@code DisbursementRequested}; gọi lặp với cùng hồ sơ không mở
     * saga thứ hai.
     */
    void start(LoanContract contract, LoanApplication application, List<ContractParty> parties, Instant now);

    /** Payment đã chuyển tiền: saga sang chờ ghi core; event đã xử lý bị bỏ qua. */
    void handleCompleted(UUID eventId, Instant occurredAt, DisbursementCompletedEventData eventData);

    /**
     * Payment thất bại: khi saga còn chờ Payment thì hủy gọi vốn để trả tiền giữ; event đã xử lý bị
     * bỏ qua.
     */
    void handleFailed(UUID eventId, Instant occurredAt, DisbursementFailedEventData eventData);

    /** Saga chờ ghi core, chờ retry, hoặc đang ghi core nhưng đã hết lease (worker trước có thể đã chết). */
    List<Long> dueIds();

    /**
     * Khóa saga và nhận một lần ghi core. Row lock bảo đảm hai worker cùng thấy lease hết hạn chỉ một bên nhận; bên còn
     * lại thấy lease mới và trả {@code null}. Lease hết hạn quá số lần cho phép thì dừng ở {@code REPAIR_REQUIRED}.
     */
    CoreBookingWork claim(Long id);

    /** Nhận lượt, ghi khoản vay vào core rồi ghi kết quả; mỗi bước ghi database là một transaction riêng. */
    void execute(Long id);

    /** Hoàn tất saga khi chỉ biết ID khoản vay trên core. */
    void complete(UUID sagaId, int attempt, Long fineractLoanId);

    /**
     * Hoàn tất saga khi biết ID khoản vay và ngày đáo hạn trên core.
     *
     * @param maturityDate ngày đáo hạn của khoản vay trên core, gửi kèm {@code LoanDisbursed} (B34); có thể
     *                     {@code null}
     */
    void complete(UUID sagaId, int attempt, Long fineractLoanId, java.time.LocalDate maturityDate);

    /**
     * Hoàn tất saga và ghi outbox {@code LoanDisbursed} cùng transaction. Ngày đáo hạn (B34) và kỳ đầu
     * ({@code nextDueDate/nextDueAmount}, P3) lấy nguyên từ kết quả ghi core, tức cùng một lần đọc Fineract; không có thì
     * event bỏ trống các field đó.
     */
    void complete(UUID sagaId, int attempt, CoreLoanBookingGateway.CoreLoanBookingResult bookingResult);

    /**
     * Ghi lỗi của lần ghi core: còn lượt và lỗi tạm thời thì hẹn thử lại, ngược lại chờ vận hành
     * (REPAIR_REQUIRED).
     */
    void fail(UUID sagaId, int attempt, String code, String detail, boolean retryable);

    /** {@code attempt} là claim token: kết quả chỉ được ghi khi saga vẫn ở đúng lần thử này. */
    record CoreBookingWork(UUID sagaId, int attempt, CoreLoanBookingGateway.CoreLoanBookingCommand command) {}
}
