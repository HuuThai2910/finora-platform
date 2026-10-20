package com.finora.blockchain.integration.proof.fabric;

import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.Network;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.SubmittedTransaction;
import org.hyperledger.fabric.protos.common.Block;
import org.hyperledger.fabric.protos.gateway.ErrorDetail;
import org.hyperledger.fabric.protos.peer.TxValidationCode;

/**
 * Gọi chaincode qua Fabric Gateway SDK. Gateway của peer0.org1 tự xin endorsement của cả hai tổ chức theo
 * endorsement policy MAJORITY, gửi orderer và báo trạng thái commit.
 *
 * <p>Phân loại lỗi: lỗi nghiệp vụ của chaincode ({@code PROOF_CONFLICT/PROOF_INVALID/PROOF_FORBIDDEN}) và lỗi danh
 * tính/tham số là vĩnh viễn → DEAD_LETTER; mất kết nối, timeout, chưa có chaincode, xung đột MVCC là tạm thời → retry
 * có backoff. Timeout khi chờ commit không chắc chắn kết quả nên luôn retry, lần sau đối chiếu bằng GetProof.</p>
 */
@Slf4j
public final class GatewayFabricLedgerClient implements FabricLedgerClient {

    private static final List<String> CHAINCODE_ERROR_CODES =
            List.of("PROOF_CONFLICT", "PROOF_INVALID", "PROOF_FORBIDDEN");
    private static final int MAX_DETAIL_LENGTH = 400;

    private final Network network;
    private final Contract contract;
    private final String channelName;

    public GatewayFabricLedgerClient(Network network, String chaincodeName) {
        this.network = network;
        this.contract = network.getContract(chaincodeName);
        this.channelName = network.getName();
    }

    @Override
    public byte[] evaluate(String function, String... arguments) {
        try {
            return contract.evaluateTransaction(function, arguments);
        } catch (GatewayException exception) {
            throw classify("EVALUATE", exception);
        }
    }

    @Override
    public FabricCommit submit(String function, String... arguments) {
        SubmittedTransaction submitted;
        try {
            submitted = contract.newProposal(function).addArguments(arguments).build().endorse().submitAsync();
        } catch (EndorseException exception) {
            throw classify("ENDORSE", exception);
        } catch (SubmitException exception) {
            throw classify("SUBMIT", exception);
        }
        org.hyperledger.fabric.client.Status status;
        try {
            status = submitted.getStatus();
        } catch (CommitStatusException exception) {
            // Giao dịch đã tới orderer nhưng chưa biết kết quả: retry, lần sau GetProof sẽ thấy nếu đã commit.
            throw new ProofLedgerException("FABRIC_COMMIT_STATUS_UNKNOWN",
                    limited("Chưa nhận được trạng thái commit của " + submitted.getTransactionId() + ": "
                            + describe(exception)), true);
        }
        if (!status.isSuccessful()) {
            throw commitFailure(status.getTransactionId(), status.getCode());
        }
        return new FabricCommit(submitted.getResult(), status.getTransactionId(), status.getBlockNumber());
    }

    @Override
    public OptionalLong blockNumberOf(String transactionId) {
        try {
            byte[] block = network.getContract("qscc").evaluateTransaction("GetBlockByTxID", channelName, transactionId);
            return OptionalLong.of(Block.parseFrom(block).getHeader().getNumber());
        } catch (GatewayException | InvalidProtocolBufferException exception) {
            // Receipt vẫn đúng với transaction ID; chỉ thiếu số block hiển thị.
            log.warn("Không tra được block của giao dịch Fabric: txId={}, exceptionType={}",
                    transactionId, exception.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    @Override
    public String channelName() {
        return channelName;
    }

    static ProofLedgerException classify(String operation, GatewayException exception) {
        String detail = describe(exception);
        for (String code : CHAINCODE_ERROR_CODES) {
            if (detail.contains(code)) {
                return new ProofLedgerException("FABRIC_" + code, limited(detail), false);
            }
        }
        Status.Code grpcCode = exception.getStatus().getCode();
        boolean retryable = switch (grpcCode) {
            case INVALID_ARGUMENT, PERMISSION_DENIED, UNAUTHENTICATED -> false;
            default -> true;
        };
        String errorCode = switch (grpcCode) {
            case UNAVAILABLE -> "FABRIC_UNAVAILABLE";
            case DEADLINE_EXCEEDED -> "FABRIC_TIMEOUT";
            case PERMISSION_DENIED, UNAUTHENTICATED -> "FABRIC_IDENTITY_REJECTED";
            case INVALID_ARGUMENT -> "FABRIC_REQUEST_REJECTED";
            default -> "FABRIC_" + operation + "_FAILED";
        };
        return new ProofLedgerException(errorCode, limited(detail), retryable);
    }

    static ProofLedgerException commitFailure(String transactionId, TxValidationCode code) {
        boolean retryable = code == TxValidationCode.MVCC_READ_CONFLICT
                || code == TxValidationCode.PHANTOM_READ_CONFLICT
                || code == TxValidationCode.DUPLICATE_TXID;
        String errorCode = retryable ? "FABRIC_TX_CONFLICT" : "FABRIC_TX_INVALID";
        return new ProofLedgerException(errorCode,
                limited("Giao dịch " + transactionId + " bị peer đánh dấu " + code.name()), retryable);
    }

    /** Mô tả lỗi đã lọc: trạng thái gRPC và thông điệp từng peer, không có payload hay khóa. */
    static String describe(GatewayException exception) {
        Status status = exception.getStatus();
        String details = exception.getDetails().stream()
                .map(GatewayFabricLedgerClient::describe)
                .collect(Collectors.joining("; "));
        String description = status.getDescription() == null ? "" : status.getDescription();
        return (status.getCode().name() + " " + description + (details.isEmpty() ? "" : " | " + details)).trim();
    }

    private static String describe(ErrorDetail detail) {
        return detail.getMspId() + "@" + detail.getAddress() + ": " + detail.getMessage();
    }

    private static String limited(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= MAX_DETAIL_LENGTH ? normalized : normalized.substring(0, MAX_DETAIL_LENGTH);
    }
}
