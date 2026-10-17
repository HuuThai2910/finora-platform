package com.finora.loan.repository.operations;

import com.finora.common.observability.OperationalMetricsProperties;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu đếm cho gauge vận hành của Loan (W3E, D58). Chỉ đọc database của Loan.
 *
 * <p>Mỗi câu đếm có trần ({@code LIMIT countCap} trong subquery) và query timeout riêng, để hàng đợi sự cố dồn nhiều dòng
 * không biến lần đếm thành quét không giới hạn. Index được dùng (kiểm bằng EXPLAIN trong
 * {@code LoanOperationalMetricsRepositoryIT}):</p>
 * <ul>
 *   <li>outbox {@code PENDING}: index một phần {@code idx_loan_outbox_events_due} (WHERE status = 'PENDING');</li>
 *   <li>tuổi event chưa gửi: {@code idx_loan_outbox_events_due} và {@code idx_loan_outbox_events_processing_lease},
 *       đọc heap đúng các dòng chưa gửi;</li>
 *   <li>saga {@code REPAIR_REQUIRED}: {@code idx_loan_disbursement_sagas_status_updated} (status đứng đầu);</li>
 *   <li>sự cố đối soát servicing {@code OPEN}: {@code idx_loan_reconciliation_admin_queue};</li>
 *   <li>event trả nợ bị giữ {@code PENDING}: {@code idx_loan_repayment_event_quarantine_work}.</li>
 * </ul>
 * <p>Outbox {@code DEAD_LETTER} không có index: câu đếm quét tuần tự bảng outbox (vài chục dòng mỗi khoản vay) một lần
 * mỗi chu kỳ làm mới. Khi bảng lớn, thêm index một phần {@code WHERE status = 'DEAD_LETTER'} trong migration của Loan.</p>
 */
@Repository
public class LoanOperationalMetricsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final int countCap;

    public LoanOperationalMetricsRepository(DataSource dataSource, OperationalMetricsProperties properties) {
        // JdbcTemplate riêng: query timeout chỉ áp cho câu đếm vận hành, không đổi JdbcTemplate dùng chung của service.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(properties.queryTimeoutSeconds());
        this.jdbcTemplate = template;
        this.countCap = properties.countCap();
    }

    public long pendingOutboxEvents() {
        return boundedCount("loan_outbox_events WHERE status = 'PENDING'");
    }

    public long deadLetterOutboxEvents() {
        return boundedCount("loan_outbox_events WHERE status = 'DEAD_LETTER'");
    }

    /** Số giây kể từ lúc tạo của event chưa gửi được cũ nhất (PENDING kể cả đang chờ retry, hoặc PROCESSING). */
    public double oldestUnpublishedOutboxAgeSeconds() {
        return ageSeconds("loan_outbox_events WHERE status IN ('PENDING', 'PROCESSING')");
    }

    /** Saga đã giải ngân tiền nhưng ghi Fineract lỗi, chờ vận hành sửa và đưa lại hàng đợi (R2). */
    public long disbursementSagasRepairRequired() {
        return boundedCount("loan_disbursement_sagas WHERE status = 'REPAIR_REQUIRED'");
    }

    /** Snapshot servicing lệch định danh/tiền với Fineract, đang mở (V23). */
    public long openServicingReconciliationIncidents() {
        return boundedCount("loan_reconciliation_incidents WHERE status = 'OPEN'");
    }

    /** Event RepaymentDistributed hợp lệ nhưng chưa áp được (thiếu mapping), chờ replay (V24). */
    public long pendingRepaymentEventQuarantine() {
        return boundedCount("loan_repayment_event_quarantine WHERE status = 'PENDING'");
    }

    private long boundedCount(String fromWhere) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM " + fromWhere + " LIMIT ?) AS bounded", Long.class, countCap);
        return count == null ? 0 : count;
    }

    private double ageSeconds(String fromWhere) {
        Double age = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - min(created_at)))::float8 FROM " + fromWhere, Double.class);
        // Không còn dòng nào thì min(...) là NULL; created_at tương lai (đồng hồ E2E lệch) không tính là trễ.
        return age == null ? 0 : Math.max(0, age);
    }
}
