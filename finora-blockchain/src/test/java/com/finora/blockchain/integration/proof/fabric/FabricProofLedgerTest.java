package com.finora.blockchain.integration.proof.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofReceiptFormat;
import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.integration.proof.ProofLedgerCommand;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FabricProofLedgerTest {

    private static final UUID PROOF_ID = UUID.fromString("fffddeea-029f-5526-b15b-ecc6a16fa12c");
    private static final String HASH = "e".repeat(64);
    private static final String ORIGINAL_TX = "1".repeat(64);
    private static final String NEW_TX = "2".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesWhenTheChainDoesNotHaveTheProofAndUsesTheCommittedBlock() {
        FakeClient client = new FakeClient();
        client.submitResult = record(HASH, NEW_TX);
        client.submitTransactionId = NEW_TX;
        client.submitBlock = 9;

        ProofLedgerReceipt receipt = new FabricProofLedger(client, mapper).submit(command(HASH));

        assertThat(receipt.transactionId()).isEqualTo(NEW_TX);
        assertThat(receipt.blockReference()).isEqualTo("finora-channel/9");
        assertThat(client.calls).containsExactly("evaluate:GetProof", "submit:RegisterProof");
        assertThat(ProofReceiptFormat.matches(ProofProviderType.HYPERLEDGER_FABRIC, receipt.transactionId(),
                receipt.blockReference())).isTrue();
    }

    @Test
    void retryAfterUnknownOutcomeReusesTheOriginalTransactionInsteadOfWritingAgain() {
        FakeClient client = new FakeClient();
        client.evaluateResult = record(HASH, ORIGINAL_TX);
        client.blockOfTransaction = OptionalLong.of(4);

        ProofLedgerReceipt receipt = new FabricProofLedger(client, mapper).submit(command(HASH));

        assertThat(receipt.transactionId()).isEqualTo(ORIGINAL_TX);
        assertThat(receipt.blockReference()).isEqualTo("finora-channel/4");
        assertThat(client.calls).containsExactly("evaluate:GetProof", "block:" + ORIGINAL_TX);
    }

    @Test
    void sameProofIdWithDifferentHashOnChainIsAPermanentConflict() {
        FakeClient client = new FakeClient();
        client.evaluateResult = record("f".repeat(64), ORIGINAL_TX);

        assertThatThrownBy(() -> new FabricProofLedger(client, mapper).submit(command(HASH)))
                .isInstanceOf(ProofLedgerException.class)
                .extracting("errorCode", "retryable")
                .containsExactly("FABRIC_PROOF_CONFLICT", false);
        assertThat(client.calls).doesNotContain("submit:RegisterProof");
    }

    @Test
    void racingWriterIsDetectedFromChaincodeResultAndItsBlockIsLookedUp() {
        FakeClient client = new FakeClient();
        client.submitResult = record(HASH, ORIGINAL_TX);
        client.submitTransactionId = NEW_TX;
        client.submitBlock = 10;
        client.blockOfTransaction = OptionalLong.empty();

        ProofLedgerReceipt receipt = new FabricProofLedger(client, mapper).submit(command(HASH));

        assertThat(receipt.transactionId()).isEqualTo(ORIGINAL_TX);
        assertThat(receipt.blockReference()).isNull();
    }

    @Test
    void readReturnsEmptyWhenChaincodeAnswersWithEmptyPayloadAndRejectsGarbage() {
        FakeClient client = new FakeClient();
        FabricProofLedger ledger = new FabricProofLedger(client, mapper);

        assertThat(ledger.read(PROOF_ID)).isEmpty();

        client.evaluateResult = "not-json".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ledger.read(PROOF_ID))
                .isInstanceOf(ProofLedgerException.class)
                .extracting("errorCode")
                .isEqualTo("FABRIC_RESPONSE_INVALID");
    }

    private static ProofLedgerCommand command(String hash) {
        return new ProofLedgerCommand(PROOF_ID, "LoanContract", "LC-2026-000123", ProofType.CONTRACT_DOCUMENT, hash, 2,
                "finora-loan");
    }

    private static byte[] record(String hash, String txId) {
        return ("{\"proofId\":\"" + PROOF_ID + "\",\"proofType\":\"CONTRACT_DOCUMENT\",\"aggregateType\":\"LoanContract\","
                + "\"aggregateId\":\"LC-2026-000123\",\"payloadHash\":\"" + hash + "\",\"payloadVersion\":2,"
                + "\"sourceService\":\"finora-loan\",\"txId\":\"" + txId + "\",\"recordedAt\":\"2026-10-17T03:00:00Z\","
                + "\"submitterMspId\":\"Org1MSP\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeClient implements FabricLedgerClient {
        private final List<String> calls = new ArrayList<>();
        private byte[] evaluateResult = new byte[0];
        private byte[] submitResult;
        private String submitTransactionId;
        private long submitBlock;
        private OptionalLong blockOfTransaction = OptionalLong.empty();

        @Override
        public byte[] evaluate(String function, String... arguments) {
            calls.add("evaluate:" + function);
            return evaluateResult;
        }

        @Override
        public FabricCommit submit(String function, String... arguments) {
            calls.add("submit:" + function);
            assertThat(arguments).containsExactly(PROOF_ID.toString(), "CONTRACT_DOCUMENT", "LoanContract",
                    "LC-2026-000123", HASH, "2", "finora-loan");
            return new FabricCommit(submitResult, submitTransactionId, submitBlock);
        }

        @Override
        public OptionalLong blockNumberOf(String transactionId) {
            calls.add("block:" + transactionId);
            return blockOfTransaction;
        }

        @Override
        public String channelName() {
            return "finora-channel";
        }
    }
}
