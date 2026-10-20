package com.finora.investment.repository.operations;

import com.finora.common.observability.OperationalMetricsProperties;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu đếm cho gauge vận hành của Investment (W3E, D58). Chỉ đọc database của Investment.
 *
 * <p>Sự cố lệnh góp vốn tách làm ba loại rời nhau, tổng bằng đúng hàng đợi {@code GET /investments/admin/order-incidents}
 * ({@code InvestmentOrderRepository.findIncidents}). Truy vấn admin là một OR không dùng được index; tách ra thì hai
 * loại đầu đi theo index một phần đã có (kiểm bằng EXPLAIN trong {@code InvestmentOperationalMetricsRepositoryIT}):</p>
 * <ul>
 *   <li>{@code order_pending_funds_stuck}: {@code ix_order_pending_due} (WHERE status = 'PENDING_FUNDS');</li>
 *   <li>{@code order_release_retrying}: {@code ix_order_release_due} (khoản giữ chưa nhả, chưa bị dừng);</li>
 *   <li>{@code order_release_blocked}: không có index ({@code release_blocked_at IS NOT NULL} hiếm và bảng lệnh nhỏ),
 *       quét tuần tự mỗi chu kỳ làm mới; khi bảng lớn thêm index một phần trên cột này.</li>
 * </ul>
 * <p>Outbox: {@code PENDING} và tuổi event chưa gửi đi theo {@code idx_investment_outbox_events_due}; {@code DEAD_LETTER}
 * quét tuần tự như Loan/Payment.</p>
 */
@Repository
public class InvestmentOperationalMetricsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final int countCap;

    public InvestmentOperationalMetricsRepository(DataSource dataSource, OperationalMetricsProperties properties) {
        // JdbcTemplate riêng: query timeout chỉ áp cho câu đếm vận hành, không đổi JdbcTemplate dùng chung của service.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(properties.queryTimeoutSeconds());
        this.jdbcTemplate = template;
        this.countCap = properties.countCap();
    }

    public long pendingOutboxEvents() {
        return boundedCount("investment_outbox_events WHERE status = 'PENDING'");
    }

    public long deadLetterOutboxEvents() {
        return boundedCount("investment_outbox_events WHERE status = 'DEAD_LETTER'");
    }

    /** Số giây kể từ lúc tạo của event chưa gửi được cũ nhất (PENDING kể cả chờ retry, hoặc PROCESSING). */
    public double oldestUnpublishedOutboxAgeSeconds() {
        Double age = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - min(created_at)))::float8 FROM investment_outbox_events"
                        + " WHERE status IN ('PENDING', 'PROCESSING')", Double.class);
        return age == null ? 0 : Math.max(0, age);
    }

    /** Lệnh chờ giữ tiền đã thử lại từ {@code attemptThreshold} lần mà chưa có kết quả chắc chắn. */
    public long ordersPendingFundsStuck(int attemptThreshold) {
        return boundedCount("investment_orders WHERE status = 'PENDING_FUNDS' AND release_blocked_at IS NULL"
                + " AND pending_attempt_count >= ?", attemptThreshold);
    }

    /** Lệnh đã kết thúc còn khoản giữ chưa nhả, worker đã thử lại từ {@code attemptThreshold} lần. */
    public long ordersReleaseRetrying(int attemptThreshold) {
        return boundedCount("investment_orders WHERE status IN ('CANCELLED', 'REJECTED')"
                + " AND payment_hold_reference IS NOT NULL AND payment_released_at IS NULL"
                + " AND release_blocked_at IS NULL AND release_attempt_count >= ?", attemptThreshold);
    }

    /** Nhả khoản giữ bị dừng vì Payment trả lỗi chắc chắn; chỉ vận hành mở lại được. */
    public long ordersReleaseBlocked() {
        return boundedCount("investment_orders WHERE release_blocked_at IS NOT NULL");
    }

    private long boundedCount(String fromWhere, Object... parameters) {
        Object[] arguments = new Object[parameters.length + 1];
        System.arraycopy(parameters, 0, arguments, 0, parameters.length);
        arguments[parameters.length] = countCap;
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM " + fromWhere + " LIMIT ?) AS bounded", Long.class, arguments);
        return count == null ? 0 : count;
    }
}
