package com.finora.payment.repository.operations;

import com.finora.common.observability.OperationalMetricsProperties;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu đếm cho gauge vận hành của Payment (W3E, D58). Chỉ đọc database của Payment.
 *
 * <p>Mỗi câu đếm có trần ({@code LIMIT countCap}) và query timeout riêng. Index được dùng (kiểm bằng EXPLAIN trong
 * {@code PaymentOperationalMetricsRepositoryIT}):</p>
 * <ul>
 *   <li>outbox {@code PENDING} và tuổi event chưa gửi: index một phần {@code idx_payment_outbox_due};</li>
 *   <li>trả nợ {@code RECONCILIATION_REQUIRED}: index một phần {@code idx_payment_repayments_loan_in_flight}
 *       (chỉ chứa khoản đang ghi core), kiểm lại status trên heap của đúng các dòng đó;</li>
 *   <li>giải ngân {@code RECONCILIATION_REQUIRED}: {@code idx_payment_disbursements_incident_queue};</li>
 *   <li>nạp tiền {@code RECONCILIATION_REQUIRED}: {@code idx_payment_top_ups_pending}.</li>
 * </ul>
 * <p>Outbox {@code DEAD_LETTER} không có index: quét tuần tự bảng outbox mỗi chu kỳ làm mới; khi bảng lớn thêm index một
 * phần {@code WHERE status = 'DEAD_LETTER'} trong migration của Payment.</p>
 */
@Repository
public class PaymentOperationalMetricsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final int countCap;

    public PaymentOperationalMetricsRepository(DataSource dataSource, OperationalMetricsProperties properties) {
        // JdbcTemplate riêng: query timeout chỉ áp cho câu đếm vận hành, không đổi JdbcTemplate dùng chung của service.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(properties.queryTimeoutSeconds());
        this.jdbcTemplate = template;
        this.countCap = properties.countCap();
    }

    public long pendingOutboxEvents() {
        return boundedCount("payment_outbox_events WHERE status = 'PENDING'");
    }

    public long deadLetterOutboxEvents() {
        return boundedCount("payment_outbox_events WHERE status = 'DEAD_LETTER'");
    }

    /** Số giây kể từ lúc tạo của event chưa gửi được cũ nhất (Payment outbox không có trạng thái PROCESSING). */
    public double oldestUnpublishedOutboxAgeSeconds() {
        Double age = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - min(created_at)))::float8 FROM payment_outbox_events"
                        + " WHERE status = 'PENDING'", Double.class);
        // Không còn dòng nào thì min(...) là NULL; created_at tương lai (đồng hồ E2E lệch) không tính là trễ.
        return age == null ? 0 : Math.max(0, age);
    }

    /** Khoản trả nợ đã trừ ví nhưng chưa chắc Fineract đã ghi: đối soát tự động hoặc admin "Ghi lại" (B32, D28). */
    public long repaymentsReconciliationRequired() {
        return boundedCount("payment_repayments WHERE status = 'RECONCILIATION_REQUIRED'");
    }

    /** Lệnh giải ngân chưa rõ provider đã chuyển tiền chưa: tra cứu theo sagaId, không chuyển lại (B09). */
    public long disbursementsReconciliationRequired() {
        return boundedCount("payment_disbursements WHERE status = 'RECONCILIATION_REQUIRED'");
    }

    /** Lệnh nạp tiền chưa rõ kết quả provider (callback mất hoặc lệch). */
    public long topUpsReconciliationRequired() {
        return boundedCount("payment_top_ups WHERE status = 'RECONCILIATION_REQUIRED'");
    }

    private long boundedCount(String fromWhere) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM " + fromWhere + " LIMIT ?) AS bounded", Long.class, countCap);
        return count == null ? 0 : count;
    }
}
