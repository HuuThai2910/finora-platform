package com.finora.blockchain.integration.proof.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;
import org.hyperledger.fabric.protos.peer.TxValidationCode;
import org.junit.jupiter.api.Test;

class GatewayFabricLedgerClientTest {

    @Test
    void chaincodeConflictReportedByAnEndorserIsPermanent() {
        EndorseException exception = new EndorseException("tx-1", StatusProto.toStatusRuntimeException(
                com.google.rpc.Status.newBuilder()
                        .setCode(Code.ABORTED_VALUE)
                        .setMessage("failed to endorse transaction, see attached details for more info")
                        .addDetails(Any.pack(ErrorDetail.newBuilder()
                                .setAddress("peer0.org2.finora.local:9051")
                                .setMspId("Org2MSP")
                                .setMessage("chaincode response 500, PROOF_CONFLICT: proofId đã ghi với nội dung khác")
                                .build()))
                        .build()));

        ProofLedgerException classified = GatewayFabricLedgerClient.classify("ENDORSE", exception);

        assertThat(classified.getErrorCode()).isEqualTo("FABRIC_PROOF_CONFLICT");
        assertThat(classified.isRetryable()).isFalse();
        assertThat(classified.getMessage()).contains("Org2MSP@peer0.org2.finora.local:9051");
    }

    @Test
    void unavailablePeerAndTimeoutAreRetryable() {
        ProofLedgerException unavailable = GatewayFabricLedgerClient.classify("EVALUATE",
                new GatewayException(Status.UNAVAILABLE.withDescription("connection refused").asRuntimeException()));
        ProofLedgerException timeout = GatewayFabricLedgerClient.classify("COMMIT_STATUS",
                new CommitStatusException("tx-2", Status.DEADLINE_EXCEEDED.asRuntimeException()));

        assertThat(unavailable.getErrorCode()).isEqualTo("FABRIC_UNAVAILABLE");
        assertThat(unavailable.isRetryable()).isTrue();
        assertThat(timeout.getErrorCode()).isEqualTo("FABRIC_TIMEOUT");
        assertThat(timeout.isRetryable()).isTrue();
    }

    @Test
    void rejectedIdentityIsPermanentUntilOperatorFixesIt() {
        ProofLedgerException rejected = GatewayFabricLedgerClient.classify("ENDORSE",
                new GatewayException(Status.PERMISSION_DENIED.withDescription("access denied").asRuntimeException()));

        assertThat(rejected.getErrorCode()).isEqualTo("FABRIC_IDENTITY_REJECTED");
        assertThat(rejected.isRetryable()).isFalse();
    }

    @Test
    void chaincodeNotYetDeployedIsRetryable() {
        ProofLedgerException notReady = GatewayFabricLedgerClient.classify("EVALUATE",
                new GatewayException(Status.UNKNOWN.withDescription("chaincode finora-ledger not found").asRuntimeException()));

        assertThat(notReady.getErrorCode()).isEqualTo("FABRIC_EVALUATE_FAILED");
        assertThat(notReady.isRetryable()).isTrue();
    }

    @Test
    void mvccConflictRetriesButInvalidEndorsementIsPermanent() {
        ProofLedgerException mvcc = GatewayFabricLedgerClient.commitFailure("tx-3", TxValidationCode.MVCC_READ_CONFLICT);
        ProofLedgerException policy = GatewayFabricLedgerClient.commitFailure("tx-4",
                TxValidationCode.ENDORSEMENT_POLICY_FAILURE);

        assertThat(mvcc.getErrorCode()).isEqualTo("FABRIC_TX_CONFLICT");
        assertThat(mvcc.isRetryable()).isTrue();
        assertThat(policy.getErrorCode()).isEqualTo("FABRIC_TX_INVALID");
        assertThat(policy.isRetryable()).isFalse();
    }
}
