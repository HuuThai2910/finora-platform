package com.finora.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionProcessor;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import com.finora.common.exception.BusinessException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Xác nhận Blockchain khởi động với đúng PostgreSQL 17 và Flyway không còn migration chờ chạy.
 */
@SpringBootTest
@Testcontainers
class FinoraBlockchainApplicationIT {

    private static final DockerImageName POSTGRESQL_17 = DockerImageName.parse("postgres:17.5-alpine");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(POSTGRESQL_17)
            .withDatabaseName("finora_blockchain_test")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private ProofSubmissionRepository proofRepository;

    @Autowired
    private ProofSubmissionService proofService;

    @Autowired
    private ProofSubmissionProcessor proofProcessor;

    @BeforeEach
    void cleanProofs() {
        proofRepository.deleteAll();
    }

    @Test
    void contextUsesPostgreSql17AndHasNoPendingMigration() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);

        assertThat(version).startsWith("17.");
        assertThat(flyway.info().pending()).isEmpty();
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'blockchain_proof_submissions'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);
    }

    @Test
    void duplicateSourceEventReturnsSameProofAndDifferentPayloadIsRejected() {
        UUID sourceEventId = UUID.randomUUID();
        ProofRegistrationCommand command = command(sourceEventId, "a".repeat(64));

        ProofSubmissionView first = proofService.register(command);
        ProofSubmissionView duplicate = proofService.register(command);

        assertThat(duplicate.proofId()).isEqualTo(first.proofId());
        assertThat(proofRepository.count()).isEqualTo(1);
        assertThatThrownBy(() -> proofService.register(command(sourceEventId, "b".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("PROOF_SOURCE_EVENT_CONFLICT");
    }

    @Test
    void concurrentDuplicateRegistrationCreatesOneProof() throws Exception {
        ProofRegistrationCommand command = command(UUID.randomUUID(), "d".repeat(64));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ProofSubmissionView> first = executor.submit(() -> registerAfterBarrier(command, ready, start));
            Future<ProofSubmissionView> second = executor.submit(() -> registerAfterBarrier(command, ready, start));
            ready.await();
            start.countDown();

            assertThat(first.get().proofId()).isEqualTo(second.get().proofId());
            assertThat(proofRepository.count()).isEqualTo(1);
        }
    }

    @Test
    void mockProviderConfirmsHashOnlyProof() {
        ProofSubmissionView registered = proofService.register(command(UUID.randomUUID(), "c".repeat(64)));
        ProofSubmission proof = proofRepository.findAll().getFirst();

        proofProcessor.process(proof.getId());

        ProofSubmission confirmed = proofRepository.findById(proof.getId()).orElseThrow();
        assertThat(registered.status()).isEqualTo(ProofSubmissionStatus.PENDING);
        assertThat(confirmed.getStatus()).isEqualTo(ProofSubmissionStatus.CONFIRMED);
        assertThat(confirmed.getProviderTransactionId()).startsWith("MOCK-");
        assertThat(confirmed.getProviderBlockReference()).isEqualTo("MOCK-BLOCK-NOT-ON-CHAIN");
        assertThat(confirmed.getPayloadHash()).hasSize(64);
    }

    private static ProofRegistrationCommand command(UUID sourceEventId, String hash) {
        return new ProofRegistrationCommand(
                "finora-loan", sourceEventId, "LOAN_CONTRACT", "LC-001",
                ProofType.CONTRACT_DOCUMENT, hash, 1);
    }

    private ProofSubmissionView registerAfterBarrier(
            ProofRegistrationCommand command,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return proofService.register(command);
    }
}
