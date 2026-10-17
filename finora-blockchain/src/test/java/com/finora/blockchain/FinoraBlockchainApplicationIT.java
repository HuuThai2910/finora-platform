package com.finora.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.messaging.MilestoneProofRegistrar;
import com.finora.blockchain.messaging.ProofMilestone;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionProcessor;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import com.finora.blockchain.service.proof.ProofSubmissionStateService;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import com.finora.common.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * PostgreSQL 17 thật + ledger MOCK (mặc định): migration V2, idempotency hai lớp, đăng ký bốn mốc từ event, ràng buộc
 * receipt theo provider và explorer/verify qua HTTP với quyền ADMIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FinoraBlockchainApplicationIT {

    private static final DockerImageName POSTGRESQL_17 = DockerImageName.parse("postgres:17.5-alpine");
    private static final String CONTRACT_DATA = "{\"loanApplicationId\":123,\"applicationNumber\":\"LA-2026-000123\","
            + "\"contractNumber\":\"LC-2026-000123\",\"listingId\":456,"
            + "\"documentHash\":\"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4\","
            + "\"pdfReceiptHash\":\"2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae\","
            + "\"allocationVersion\":1,"
            + "\"allocationHash\":\"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9\","
            + "\"activatedAt\":\"2026-10-17T03:00:00Z\"}";

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
    @Autowired
    private ProofSubmissionStateService stateService;
    @Autowired
    private MilestoneProofRegistrar registrar;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void cleanProofs() {
        proofRepository.deleteAll();
    }

    @Test
    void contextUsesPostgreSql17AndHasNoPendingMigration() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);

        assertThat(version).startsWith("17.");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
    }

    @Test
    void duplicateSourceEventReturnsSameProofAndDifferentPayloadIsRejected() {
        UUID sourceEventId = UUID.randomUUID();
        ProofRegistrationCommand command = command(sourceEventId, "LC-001", "a".repeat(64));

        ProofSubmissionView first = proofService.register(command);
        ProofSubmissionView duplicate = proofService.register(command);

        assertThat(duplicate.proofId()).isEqualTo(first.proofId());
        assertThat(proofRepository.count()).isEqualTo(1);
        assertThatThrownBy(() -> proofService.register(command(sourceEventId, "LC-001", "b".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("PROOF_SOURCE_EVENT_CONFLICT");
    }

    @Test
    void sameMilestoneRepublishedWithNewEventIdDoesNotCreateSecondLogicalProof() {
        ProofSubmissionView first = proofService.register(command(UUID.randomUUID(), "LC-002", "a".repeat(64)));
        ProofSubmissionView republished = proofService.register(command(UUID.randomUUID(), "LC-002", "a".repeat(64)));

        assertThat(republished.proofId()).isEqualTo(first.proofId());
        assertThat(proofRepository.count()).isEqualTo(1);
        assertThatThrownBy(() -> proofService.register(command(UUID.randomUUID(), "LC-002", "c".repeat(64))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("PROOF_MILESTONE_CONFLICT");
    }

    @Test
    void concurrentDuplicateRegistrationCreatesOneProof() throws Exception {
        ProofRegistrationCommand command = command(UUID.randomUUID(), "LC-003", "d".repeat(64));

        List<ProofSubmissionView> results = concurrently(command, command);

        assertThat(results.get(0).proofId()).isEqualTo(results.get(1).proofId());
        assertThat(proofRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentEventsOfTheSameMilestoneCreateOneProof() throws Exception {
        List<ProofSubmissionView> results = concurrently(
                command(UUID.randomUUID(), "LC-004", "d".repeat(64)),
                command(UUID.randomUUID(), "LC-004", "d".repeat(64)));

        assertThat(results.get(0).proofId()).isEqualTo(results.get(1).proofId());
        assertThat(proofRepository.count()).isEqualTo(1);
    }

    @Test
    void mockProviderConfirmsHashOnlyProof() {
        ProofSubmissionView registered = proofService.register(command(UUID.randomUUID(), "LC-005", "c".repeat(64)));
        ProofSubmission proof = proofRepository.findAll().getFirst();

        proofProcessor.process(proof.getId());

        ProofSubmission confirmed = proofRepository.findById(proof.getId()).orElseThrow();
        assertThat(registered.status()).isEqualTo(ProofSubmissionStatus.PENDING);
        assertThat(confirmed.getStatus()).isEqualTo(ProofSubmissionStatus.CONFIRMED);
        assertThat(confirmed.getProviderTransactionId()).startsWith("MOCK-");
        assertThat(confirmed.getProviderBlockReference()).isEqualTo("MOCK-BLOCK-NOT-ON-CHAIN");
        assertThat(confirmed.isOnChain()).isFalse();
    }

    @Test
    void legacyRepaymentProofStaysTheOnlyProofWhenItsEventIsReplayed() {
        UUID legacyEvent = UUID.randomUUID();
        insertLegacyRepaymentProof(legacyEvent, "REPAY-LEGACY");
        String data = "{\"repaymentId\":\"REPAY-LEGACY\",\"loanApplicationId\":77,\"amount\":\"1000000.00\"}";

        ProofSubmissionView replay = registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                envelope(legacyEvent, data), legacyEvent.toString(), "RepaymentDistributed", "1");
        ProofSubmissionView republished = registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                envelope(UUID.randomUUID(), data), null, null, null);

        assertThat(replay.proofId()).isEqualTo(republished.proofId());
        assertThat(proofRepository.count()).isEqualTo(1);
        assertThat(proofRepository.findAll().getFirst().getPayloadVersion()).isEqualTo(1);
    }

    @Test
    void databaseRefusesMockReceiptOnFabricProof() {
        UUID proofId = UUID.randomUUID();
        insertFabricPendingProof(proofId, "LC-006");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE blockchain_proof_submissions
                   SET status = 'CONFIRMED', provider_transaction_id = 'MOCK-123',
                       provider_block_reference = 'MOCK-BLOCK-NOT-ON-CHAIN', confirmed_at = now()
                 WHERE proof_id = ?
                """, proofId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void workerIgnoresProofsRegisteredForAnotherProvider() {
        insertFabricPendingProof(UUID.randomUUID(), "LC-007");
        proofService.register(command(UUID.randomUUID(), "LC-008", "e".repeat(64)));

        List<Long> due = stateService.dueIds();

        assertThat(due).hasSize(1);
        assertThat(proofRepository.findById(due.getFirst()).orElseThrow().getAggregateId()).isEqualTo("LC-008");
    }

    @Test
    void adminExploresAndVerifiesTheProofsOfALoan() throws Exception {
        UUID eventId = UUID.randomUUID();
        ProofSubmissionView contract = registrar.register(ProofMilestone.CONTRACT_SIGNED,
                envelope(eventId, CONTRACT_DATA), eventId.toString(), "LoanContractActivated", "1");
        UUID repaymentEvent = UUID.randomUUID();
        registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                envelope(repaymentEvent, "{\"repaymentId\":\"REPAY-9\",\"loanApplicationId\":123,\"amount\":\"10.00\"}"),
                null, null, null);
        proofProcessor.process(proofRepository.findByProofId(contract.proofId()).orElseThrow().getId());

        mvc.perform(get("/api/v1/blockchain/proofs").param("referenceType", "LOAN_APPLICATION").param("referenceId", "123")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].proofType").value("CONTRACT_DOCUMENT"))
                .andExpect(jsonPath("$.content[0].hash.scheme").value("CANONICAL_JSON_V2"))
                .andExpect(jsonPath("$.content[0].receipt.kind").value("MOCK_NOT_ON_CHAIN"))
                .andExpect(jsonPath("$.content[0].receipt.onChain").value(false))
                .andExpect(jsonPath("$.content[1].proofType").value("REPAYMENT"))
                .andExpect(jsonPath("$.content[1].receipt").doesNotExist());

        mvc.perform(post("/api/v1/blockchain/proofs/{id}/verify", contract.proofId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"artifact\":" + CONTRACT_DATA + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.result").value("MATCHED"))
                .andExpect(jsonPath("$.artifact.recomputedHash")
                        .value("efee2119b4714f9547d8781865a70ae390580f1a067766fd50ccb846e692a729"))
                .andExpect(jsonPath("$.chain.result").value("NOT_ON_CHAIN_MOCK"));

        String tampered = CONTRACT_DATA.replace("\"listingId\":456", "\"listingId\":457");
        mvc.perform(post("/api/v1/blockchain/proofs/{id}/verify", contract.proofId()).with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"artifact\":" + tampered + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifact.result").value("MISMATCHED"));

        mvc.perform(get("/api/v1/blockchain/proofs").param("referenceType", "LOAN_CONTRACT")
                        .param("referenceId", "LC-2026-000123").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/blockchain/proofs").param("referenceType", "SOMETHING").param("referenceId", "1")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROOF_REFERENCE_TYPE_INVALID"));
        mvc.perform(get("/api/v1/blockchain/proofs/{id}", "not-a-uuid").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROOF_ID_INVALID"));
    }

    @Test
    void operatorRequeuesDeadLetterProof() throws Exception {
        UUID proofId = UUID.randomUUID();
        insertFabricPendingProof(proofId, "LC-009");
        jdbcTemplate.update("UPDATE blockchain_proof_submissions SET status = 'DEAD_LETTER', attempt_count = 10,"
                + " last_error_code = 'FABRIC_IDENTITY_REJECTED' WHERE proof_id = ?", proofId);

        mvc.perform(post("/api/v1/blockchain/proofs/{id}/retry", proofId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0))
                .andExpect(jsonPath("$.lastErrorCode").value("FABRIC_IDENTITY_REJECTED"));
        mvc.perform(post("/api/v1/blockchain/proofs/{id}/retry", proofId).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROOF_NOT_DEAD_LETTER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static ProofRegistrationCommand command(UUID sourceEventId, String contractNumber, String hash) {
        return new ProofRegistrationCommand("finora-loan", sourceEventId, "LoanContractActivated", 1,
                Instant.parse("2026-10-17T03:00:00Z"), ProofType.CONTRACT_DOCUMENT, "LoanContract", contractNumber,
                123L, hash, 2);
    }

    private static String envelope(UUID eventId, String data) {
        return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-10-17T03:00:01Z\",\"version\":1,\"data\":" + data + "}";
    }

    private List<ProofSubmissionView> concurrently(ProofRegistrationCommand first, ProofRegistrationCommand second)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ProofSubmissionView> a = executor.submit(() -> registerAfterBarrier(first, ready, start));
            Future<ProofSubmissionView> b = executor.submit(() -> registerAfterBarrier(second, ready, start));
            ready.await();
            start.countDown();
            return List.of(a.get(), b.get());
        }
    }

    private ProofSubmissionView registerAfterBarrier(
            ProofRegistrationCommand command, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return proofService.register(command);
    }

    /** Bản ghi REPAYMENT trước W3D: proofId ngẫu nhiên, hash scheme v1, provider MOCK đã xác nhận. */
    private void insertLegacyRepaymentProof(UUID sourceEventId, String repaymentId) {
        jdbcTemplate.update("""
                INSERT INTO blockchain_proof_submissions (
                    proof_id, source_service, source_event_id, aggregate_type, aggregate_id, proof_type,
                    payload_hash, payload_version, provider, status, attempt_count, available_at,
                    provider_transaction_id, provider_block_reference, confirmed_at, version, created_at, updated_at)
                VALUES (?, 'finora-payment', ?, 'Repayment', ?, 'REPAYMENT', ?, 1, 'MOCK', 'CONFIRMED', 1, now(),
                    'MOCK-legacy', 'MOCK-BLOCK-NOT-ON-CHAIN', now(), 0, now(), now())
                """, UUID.randomUUID(), sourceEventId, repaymentId, "9".repeat(64));
    }

    private void insertFabricPendingProof(UUID proofId, String contractNumber) {
        jdbcTemplate.update("""
                INSERT INTO blockchain_proof_submissions (
                    proof_id, source_service, source_event_id, aggregate_type, aggregate_id, proof_type,
                    payload_hash, payload_version, provider, status, attempt_count, available_at,
                    version, created_at, updated_at, loan_application_id)
                VALUES (?, 'finora-loan', ?, 'LoanContract', ?, 'CONTRACT_DOCUMENT', ?, 2, 'HYPERLEDGER_FABRIC',
                    'PENDING', 0, now() - interval '1 minute', 0, now(), now(), 321)
                """, proofId, UUID.randomUUID(), contractNumber, "8".repeat(64));
    }
}
