package com.finora.common.messaging;

/**
 * Tên header Kafka của event FINORA (rule 04). Producer luôn gắn {@link #EVENT_ID}, {@link #EVENT_TYPE} và
 * {@link #EVENT_VERSION}; {@link #TRACE_ID} chỉ có khi service nguồn lưu trace id cùng outbox (Loan, Investment; Payment
 * không có). Giá trị header là chuỗi UTF-8.
 */
public final class FinoraKafkaHeaders {

    /** UUID của event, phải trùng {@code eventId} trong envelope. */
    public static final String EVENT_ID = "finora-event-id";

    /** Loại event ở thì quá khứ, ví dụ {@code LoanDisbursed}; envelope không chứa loại event. */
    public static final String EVENT_TYPE = "finora-event-type";

    /** Version của payload dạng số nguyên thập phân, phải trùng {@code version} trong envelope. */
    public static final String EVENT_VERSION = "finora-event-version";

    /** Trace id của request đã tạo event, để nối log giữa các service. */
    public static final String TRACE_ID = "finora-trace-id";

    private FinoraKafkaHeaders() {
    }
}
