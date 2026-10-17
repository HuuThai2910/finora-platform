package com.finora.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofChainCheckStatus;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.messaging.MilestoneProofRegistrar;
import com.finora.blockchain.messaging.ProofMilestone;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.service.proof.ProofSubmissionProcessor;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import com.finora.blockchain.service.proof.ProofVerificationService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Drill sự cố Fabric thật (opt-in riêng vì dừng container {@code finora-fabric-peer0-org2}): khi peer của tổ chức kiểm
 * toán tắt, không đủ endorsement MAJORITY nên proof chỉ được giữ PENDING và retry; bật lại peer thì cùng proof được
 * ghi đúng một lần. Bước đăng ký proof chỉ ghi DB Blockchain nên luồng tiền/hợp đồng ở service nguồn không bị chặn.
 * Chạy: {@code FINORA_FABRIC_OUTAGE_DRILL=true mvn -pl finora-blockchain -am verify -Dit.test=FabricOutageDrillLiveIT}.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "FINORA_FABRIC_OUTAGE_DRILL", matches = "true")
class FabricOutageDrillLiveIT {

    private static final String AUDITOR_PEER = "finora-fabric-peer0-org2";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_blockchain_fabric_drill")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired
    private MilestoneProofRegistrar registrar;
    @Autowired
    private ProofSubmissionProcessor processor;
    @Autowired
    private ProofSubmissionRepository repository;
    @Autowired
    private ProofVerificationService verification;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void fabric(DynamicPropertyRegistry registry) throws IOException {
        JsonNode client = new ObjectMapper().readTree(Files.readString(Path.of(System.getenv().getOrDefault(
                "FINORA_FABRIC_CLIENT_JSON", "../docker/fabric-network/build/client.json"))));
        registry.add("finora.blockchain.proof.provider", () -> "HYPERLEDGER_FABRIC");
        registry.add("finora.blockchain.fabric.peer-endpoint", () -> client.path("peerEndpoint").asText());
        registry.add("finora.blockchain.fabric.cert-path", () -> client.path("certPath").asText());
        registry.add("finora.blockchain.fabric.key-path", () -> client.path("keyPath").asText());
        registry.add("finora.blockchain.fabric.tls-cert-path", () -> client.path("tlsCertPath").asText());
        // Drill không chờ commit lâu: gateway báo lỗi endorsement ngay khi thiếu peer của Org2.
        registry.add("finora.blockchain.fabric.endorse-timeout", () -> "10s");
    }

    @Test
    void auditorPeerOutageOnlyDelaysTheProofAndItIsWrittenOnceAfterRecovery() throws Exception {
        String repaymentId = UUID.randomUUID().toString();
        UUID eventId = UUID.randomUUID();
        String data = "{\"repaymentId\":\"" + repaymentId + "\",\"loanApplicationId\":901,\"amount\":\"1726000.00\"}";
        ProofSubmissionView registered = registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-10-17T05:00:00Z\",\"version\":1,\"data\":" + data + "}",
                eventId.toString(), "RepaymentDistributed", "1");
        Long id = repository.findByProofId(registered.proofId()).orElseThrow().getId();

        docker("stop", AUDITOR_PEER);
        try {
            processor.process(id);
            ProofSubmission during = repository.findById(id).orElseThrow();
            System.out.println("[FABRIC-DRILL] khi peer0.org2 tắt: status=" + during.getStatus()
                    + " attempt=" + during.getAttemptCount() + " error=" + during.getLastErrorCode()
                    + " detail=" + during.getLastErrorDetail());
            assertThat(during.getStatus()).isEqualTo(ProofSubmissionStatus.PENDING);
            assertThat(during.getLastErrorCode()).startsWith("FABRIC_");
            assertThat(during.getProviderTransactionId()).isNull();
        } finally {
            docker("start", AUDITOR_PEER);
        }
        waitHealthy(AUDITOR_PEER, Duration.ofSeconds(120));

        ProofSubmission confirmed = null;
        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            // Bỏ qua backoff để drill không phải chờ; retry thật dùng retry-backoff/maximum-backoff.
            jdbcTemplate.update("UPDATE blockchain_proof_submissions SET available_at = now() WHERE id = ? "
                    + "AND status = 'PENDING'", id);
            processor.process(id);
            confirmed = repository.findById(id).orElseThrow();
            if (confirmed.getStatus() == ProofSubmissionStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(3_000);
        }
        assertThat(confirmed).isNotNull();
        assertThat(confirmed.getStatus()).as(confirmed.getLastErrorDetail()).isEqualTo(ProofSubmissionStatus.CONFIRMED);
        assertThat(verification.reconcile(id)).contains(ProofChainCheckStatus.MATCHED);
        System.out.println("[FABRIC-DRILL] sau khi bật lại: status=" + confirmed.getStatus() + " attempt="
                + confirmed.getAttemptCount() + " tx=" + confirmed.getProviderTransactionId() + " block="
                + confirmed.getProviderBlockReference() + " chainCheck=MATCHED");
    }

    private static void docker(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "docker";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static void waitHealthy(String container, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Process process = new ProcessBuilder("docker", "inspect", "--format", "{{.State.Health.Status}}", container)
                    .redirectErrorStream(true).start();
            String state = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            if ("healthy".equals(state)) {
                return;
            }
            Thread.sleep(2_000);
        }
        throw new IllegalStateException(container + " chưa healthy sau " + timeout);
    }
}
