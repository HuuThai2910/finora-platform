package com.finora.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.integration.proof.LedgerProofRecord;
import com.finora.blockchain.integration.proof.ProofLedger;
import com.finora.blockchain.integration.proof.ProofLedgerCommand;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import com.finora.blockchain.messaging.MilestoneProofRegistrar;
import com.finora.blockchain.messaging.ProofMilestone;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.service.proof.ProofSubmissionProcessor;
import com.finora.blockchain.service.proof.ProofSubmissionView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Chạy thật trên mạng Fabric local (opt-in, W3D): {@code docker/fabric-network/fabric-network.ps1 -Action Up} rồi
 * {@code FINORA_FABRIC_IT=true mvn -pl finora-blockchain -am verify -Dit.test=FabricProofFlowLiveIT}. Bốn mốc đi
 * đúng đường code production (registrar → PostgreSQL → worker → chaincode qua Gateway, cả hai tổ chức endorse) rồi
 * được kiểm bằng API verify; không đụng Kafka hay DB của stack FINORA đang chạy.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@EnabledIfEnvironmentVariable(named = "FINORA_FABRIC_IT", matches = "true")
class FabricProofFlowLiveIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRESQL = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.5-alpine"))
            .withDatabaseName("finora_blockchain_fabric_it")
            .withUsername("finora_test")
            .withPassword("finora_test");

    @Autowired
    private MilestoneProofRegistrar registrar;
    @Autowired
    private ProofSubmissionProcessor processor;
    @Autowired
    private ProofSubmissionRepository repository;
    @Autowired
    private ProofLedger ledger;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void fabric(DynamicPropertyRegistry registry) throws IOException {
        Path clientFile = Path.of(System.getenv().getOrDefault("FINORA_FABRIC_CLIENT_JSON",
                "../docker/fabric-network/build/client.json"));
        JsonNode client = new ObjectMapper().readTree(Files.readString(clientFile));
        registry.add("finora.blockchain.proof.provider", () -> "HYPERLEDGER_FABRIC");
        registry.add("finora.blockchain.fabric.peer-endpoint", () -> client.path("peerEndpoint").asText());
        registry.add("finora.blockchain.fabric.msp-id", () -> client.path("mspId").asText());
        registry.add("finora.blockchain.fabric.channel-name", () -> client.path("channelName").asText());
        registry.add("finora.blockchain.fabric.chaincode-name", () -> client.path("chaincodeName").asText());
        registry.add("finora.blockchain.fabric.cert-path", () -> client.path("certPath").asText());
        registry.add("finora.blockchain.fabric.key-path", () -> client.path("keyPath").asText());
        registry.add("finora.blockchain.fabric.tls-cert-path", () -> client.path("tlsCertPath").asText());
    }

    @Test
    void fourMilestonesAreAnchoredOnFabricAndVerifiableFromTheirArtifacts() throws Exception {
        String run = Long.toString(System.currentTimeMillis());
        String contractNumber = "LC-IT-" + run;
        String sagaId = UUID.randomUUID().toString();
        String repaymentId = UUID.randomUUID().toString();
        long loanId = 900_000_000L + (System.currentTimeMillis() % 1_000_000L);
        Map<ProofMilestone, String> artifacts = new LinkedHashMap<>();
        artifacts.put(ProofMilestone.CONTRACT_SIGNED, "{\"loanApplicationId\":" + loanId + ",\"applicationNumber\":\"LA-IT-"
                + run + "\",\"contractNumber\":\"" + contractNumber + "\",\"listingId\":" + loanId
                + ",\"documentHash\":\"" + "8f43".repeat(16) + "\",\"pdfReceiptHash\":\"" + "2c26".repeat(16)
                + "\",\"allocationVersion\":1,\"allocationHash\":\"" + "fcde".repeat(16)
                + "\",\"activatedAt\":\"2026-10-17T03:00:00Z\"}");
        artifacts.put(ProofMilestone.LOAN_DISBURSED, "{\"sagaId\":\"" + sagaId + "\",\"loanApplicationId\":" + loanId
                + ",\"applicationNumber\":\"LA-IT-" + run + "\",\"contractNumber\":\"" + contractNumber + "\",\"listingId\":"
                + loanId + ",\"amount\":\"50000000.00\",\"currency\":\"VND\",\"paymentReference\":\"MOCK-" + sagaId
                + "\",\"fineractLoanId\":77,\"disbursedAt\":\"2026-10-17T04:00:00Z\"}");
        artifacts.put(ProofMilestone.REPAYMENT_DISTRIBUTED, "{\"repaymentId\":\"" + repaymentId + "\",\"loanApplicationId\":"
                + loanId + ",\"amount\":\"1726000.00\",\"principalAmount\":\"1624000.00\",\"interestAmount\":\"102000.00\"}");
        artifacts.put(ProofMilestone.NOTE_OWNERSHIP_CHANGED, "{\"loanApplicationId\":" + loanId + ",\"listingId\":" + loanId
                + ",\"currency\":\"VND\",\"reason\":\"ISSUED\",\"reference\":\"MOCK-" + sagaId
                + "\",\"changedAt\":\"2026-10-17T04:00:00Z\",\"notes\":[{\"noteId\":1,\"noteNumber\":\"N-1\","
                + "\"investorId\":\"42\",\"outstandingPrincipal\":\"50000000.00\"}]}");

        List<String> evidence = new ArrayList<>();
        Map<ProofMilestone, ProofSubmission> confirmed = new LinkedHashMap<>();
        for (Map.Entry<ProofMilestone, String> artifact : artifacts.entrySet()) {
            UUID eventId = UUID.randomUUID();
            ProofSubmissionView registered = registrar.register(artifact.getKey(),
                    "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-10-17T05:00:00Z\",\"version\":1,\"data\":"
                            + artifact.getValue() + "}", eventId.toString(), artifact.getKey().eventType(), "1");
            ProofSubmission pending = repository.findByProofId(registered.proofId()).orElseThrow();
            assertThat(pending.getProvider()).isEqualTo(ProofProviderType.HYPERLEDGER_FABRIC);

            processor.process(pending.getId());

            ProofSubmission proof = repository.findById(pending.getId()).orElseThrow();
            assertThat(proof.getStatus()).as("%s: %s", artifact.getKey(), proof.getLastErrorDetail())
                    .isEqualTo(ProofSubmissionStatus.CONFIRMED);
            assertThat(proof.getProviderTransactionId()).matches("^[0-9a-f]{64}$");
            assertThat(proof.getProviderBlockReference()).matches("^finora-channel/[0-9]+$");
            confirmed.put(artifact.getKey(), proof);

            mvc.perform(post("/api/v1/blockchain/proofs/{id}/verify", proof.getProofId())
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"artifact\":" + artifact.getValue() + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.artifact.result").value("MATCHED"))
                    .andExpect(jsonPath("$.chain.result").value("MATCHED"))
                    .andExpect(jsonPath("$.chain.transactionId").value(proof.getProviderTransactionId()))
                    .andExpect(jsonPath("$.chain.submitterMspId").value("Org1MSP"));
            evidence.add(String.format("%-22s proofId=%s aggregate=%s/%s tx=%s block=%s hash=%s",
                    artifact.getKey(), proof.getProofId(), proof.getAggregateType(), proof.getAggregateId(),
                    proof.getProviderTransactionId(), proof.getProviderBlockReference(), proof.getPayloadHash()));
        }

        // Gửi lại sau "timeout": adapter đọc GetProof và trả đúng giao dịch gốc, chain không có bản ghi thứ hai.
        ProofSubmission contract = confirmed.get(ProofMilestone.CONTRACT_SIGNED);
        ProofLedgerCommand resend = new ProofLedgerCommand(contract.getProofId(), contract.getAggregateType(),
                contract.getAggregateId(), contract.getProofType(), contract.getPayloadHash(), contract.getPayloadVersion(),
                contract.getSourceService());
        ProofLedgerReceipt again = ledger.submit(resend);
        assertThat(again.transactionId()).isEqualTo(contract.getProviderTransactionId());

        // Cùng proofId nhưng hash khác: chain từ chối (bị phát hiện ngay ở bước đọc trước khi ghi).
        ProofLedgerCommand forged = new ProofLedgerCommand(contract.getProofId(), contract.getAggregateType(),
                contract.getAggregateId(), contract.getProofType(), "0".repeat(64), contract.getPayloadVersion(),
                contract.getSourceService());
        assertThatThrownBy(() -> ledger.submit(forged))
                .isInstanceOf(ProofLedgerException.class)
                .extracting("errorCode", "retryable")
                .containsExactly("FABRIC_PROOF_CONFLICT", false);

        // Sửa hash trong PostgreSQL (giả lập DB bị can thiệp): đối chiếu với chain phát hiện MISMATCHED.
        ProofSubmission repayment = confirmed.get(ProofMilestone.REPAYMENT_DISTRIBUTED);
        jdbcTemplate.update("UPDATE blockchain_proof_submissions SET payload_hash = ? WHERE id = ?",
                "1".repeat(64), repayment.getId());
        mvc.perform(post("/api/v1/blockchain/proofs/{id}/verify", repayment.getProofId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chain.result").value("MISMATCHED"))
                .andExpect(jsonPath("$.chain.payloadHash").value(repayment.getPayloadHash()));

        LedgerProofRecord onChain = ledger.read(repayment.getProofId()).orElseThrow();
        assertThat(onChain.payloadHash()).isEqualTo(repayment.getPayloadHash());
        evidence.add("tamper-check proofId=" + repayment.getProofId() + " chainHash=" + onChain.payloadHash()
                + " dbHash=" + "1".repeat(64) + " -> MISMATCHED");
        evidence.add("resend contract proof -> same tx " + again.transactionId());
        evidence.forEach(line -> System.out.println("[FABRIC-EVIDENCE] " + line));
    }
}
