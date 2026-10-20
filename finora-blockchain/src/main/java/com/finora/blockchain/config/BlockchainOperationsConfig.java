package com.finora.blockchain.config;

import com.finora.blockchain.repository.operations.BlockchainOperationalMetricsRepository;
import com.finora.common.observability.OperationalGauge;
import com.finora.common.observability.OperationalGaugeSet;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gauge hàng đợi proof của Blockchain (W3E, D58). Endpoint {@code /actuator/metrics} chưa mở ở Blockchain vì service
 * chưa có xác thực JWT; gauge vẫn được đăng ký để khi R6/R8 thêm security chỉ cần mở endpoint.
 */
@Configuration
public class BlockchainOperationsConfig {

    /** Số proof theo trạng thái ({@code pending}, {@code dead_letter}). */
    public static final String PROOF_SUBMISSIONS = "finora.proof.submissions";
    /** Số giây kể từ lúc tạo của proof chưa xác nhận cũ nhất. */
    public static final String PROOF_OLDEST_PENDING_AGE = "finora.proof.oldest.pending.age";

    @Bean
    OperationalGaugeSet blockchainOperationalGauges(BlockchainOperationalMetricsRepository repository) {
        return new OperationalGaugeSet("blockchain", List.of(
                new OperationalGauge(PROOF_SUBMISSIONS, "Số proof theo trạng thái (đếm có trần)", "proofs",
                        Map.of("status", "pending"), repository::pendingProofs),
                new OperationalGauge(PROOF_SUBMISSIONS, "Số proof theo trạng thái (đếm có trần)", "proofs",
                        Map.of("status", "dead_letter"), repository::deadLetterProofs),
                new OperationalGauge(PROOF_OLDEST_PENDING_AGE,
                        "Số giây kể từ lúc tạo của proof chưa xác nhận cũ nhất; 0 khi không còn proof chờ", "seconds",
                        Map.of(), repository::oldestUnconfirmedProofAgeSeconds)));
    }
}
