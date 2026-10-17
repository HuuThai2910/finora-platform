package com.finora.blockchain.repository.operations;

import com.finora.common.observability.OperationalMetricsProperties;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu đếm hàng đợi proof cho gauge vận hành của Blockchain (W3E, D58). Chỉ đọc database của Blockchain.
 *
 * <p>{@code PENDING} và tuổi proof chưa xác nhận đi theo index một phần {@code idx_blockchain_proof_submissions_due} và
 * {@code idx_blockchain_proof_submissions_processing_lease}. {@code DEAD_LETTER}: V1 không có index (quét tuần tự bảng
 * proof, một dòng mỗi event được neo); V2 của W3D thêm {@code idx_blockchain_proof_submissions_dead_letter} nên sau khi
 * merge câu đếm này dùng index đó.</p>
 */
@Repository
public class BlockchainOperationalMetricsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final int countCap;

    public BlockchainOperationalMetricsRepository(DataSource dataSource, OperationalMetricsProperties properties) {
        // JdbcTemplate riêng: query timeout chỉ áp cho câu đếm vận hành, không đổi JdbcTemplate dùng chung của service.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(properties.queryTimeoutSeconds());
        this.jdbcTemplate = template;
        this.countCap = properties.countCap();
    }

    public long pendingProofs() {
        return boundedCount("blockchain_proof_submissions WHERE status = 'PENDING'");
    }

    public long deadLetterProofs() {
        return boundedCount("blockchain_proof_submissions WHERE status = 'DEAD_LETTER'");
    }

    /** Số giây kể từ lúc tạo của proof chưa xác nhận cũ nhất (PENDING kể cả chờ retry, hoặc PROCESSING). */
    public double oldestUnconfirmedProofAgeSeconds() {
        Double age = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - min(created_at)))::float8 FROM blockchain_proof_submissions"
                        + " WHERE status IN ('PENDING', 'PROCESSING')", Double.class);
        return age == null ? 0 : Math.max(0, age);
    }

    private long boundedCount(String fromWhere) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM " + fromWhere + " LIMIT ?) AS bounded", Long.class, countCap);
        return count == null ? 0 : count;
    }
}
