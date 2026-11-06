package com.finora.payment.service.servicing;

import com.finora.payment.messaging.event.LoanDisbursedEventData;
import com.finora.payment.messaging.event.NoteOwnershipChangedEventData;

import java.util.UUID;

/**
 * Bản chiếu dữ liệu thu nợ trong Payment: tài khoản thu nợ mở khi Loan báo đã giải ngân, chủ sở hữu và dư nợ Note cập
 * nhật khi Investment báo đổi chủ. Mỗi event chỉ xử lý một lần theo {@code eventId}.
 */
public interface PaymentServicingProjectionService {

    /** Nhận {@code LoanDisbursed}: mở tài khoản thu nợ khớp với giao dịch giải ngân đã có trong Payment. */
    void activateLoan(UUID eventId, LoanDisbursedEventData data);

    /** Nhận {@code NoteOwnershipChanged}: tạo hoặc cập nhật chủ sở hữu và dư nợ gốc của từng Note. */
    void updateOwnership(UUID eventId, NoteOwnershipChangedEventData data);
}
