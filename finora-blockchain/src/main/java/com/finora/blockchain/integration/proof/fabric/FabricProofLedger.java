package com.finora.blockchain.integration.proof.fabric;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.domain.proof.ProofReceiptFormat;
import com.finora.blockchain.integration.proof.LedgerProofRecord;
import com.finora.blockchain.integration.proof.ProofLedger;
import com.finora.blockchain.integration.proof.ProofLedgerCommand;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Adapter Hyperledger Fabric thật (thay placeholder fail-closed, W3D D53).
 *
 * <p>Đối chiếu trước khi ghi: luôn đọc {@code GetProof} trước. Lần trước có thể đã commit dù client mất phản hồi
 * (timeout chờ commit, JVM tắt sau khi gửi orderer); khi đó dùng lại giao dịch gốc thay vì ghi lần hai. Chaincode cũng
 * idempotent nên hai worker đua nhau vẫn chỉ có một bản ghi.</p>
 */
@Slf4j
public final class FabricProofLedger implements ProofLedger {

    private final FabricLedgerClient client;
    private final ObjectMapper mapper;

    public FabricProofLedger(FabricLedgerClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public ProofProviderType provider() {
        return ProofProviderType.HYPERLEDGER_FABRIC;
    }

    @Override
    public ProofLedgerReceipt submit(ProofLedgerCommand command) {
        Optional<LedgerProofRecord> existing = read(command.proofId());
        if (existing.isPresent()) {
            LedgerProofRecord record = requireSameContent(existing.get(), command);
            log.info("Proof đã có trên Fabric, dùng lại giao dịch gốc: proofId={}, txId={}",
                    command.proofId(), record.txId());
            return receipt(record.txId(), client.blockNumberOf(record.txId()));
        }

        FabricLedgerClient.FabricCommit commit = client.submit("RegisterProof",
                command.proofId().toString(), command.proofType().name(), command.aggregateType(),
                command.aggregateId(), command.payloadHash(), Integer.toString(command.payloadVersion()),
                command.sourceService());
        LedgerProofRecord written = requireSameContent(parse(commit.result()), command);
        // Chaincode trả bản ghi gốc nếu proof vừa được ghi bởi giao dịch khác giữa lúc đọc và lúc gửi.
        OptionalLong block = written.txId().equals(commit.transactionId())
                ? OptionalLong.of(commit.blockNumber())
                : client.blockNumberOf(written.txId());
        log.info("Đã ghi proof lên Fabric: proofId={}, proofType={}, txId={}",
                command.proofId(), command.proofType(), written.txId());
        return receipt(written.txId(), block);
    }

    @Override
    public Optional<LedgerProofRecord> read(UUID proofId) {
        byte[] payload = client.evaluate("GetProof", proofId.toString());
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        LedgerProofRecord record = parse(payload);
        if (!proofId.equals(record.proofId())) {
            throw new ProofLedgerException("FABRIC_RESPONSE_INVALID", "Chaincode trả proof khác proofId đã hỏi", false);
        }
        return Optional.of(record);
    }

    private LedgerProofRecord requireSameContent(LedgerProofRecord record, ProofLedgerCommand command) {
        if (!record.matches(command)) {
            // Cùng proofId nhưng khác hash/aggregate: dữ liệu hai phía không cùng một sự thật → DLT để điều tra.
            throw new ProofLedgerException("FABRIC_PROOF_CONFLICT",
                    "Fabric đã có proof cùng proofId nhưng khác nội dung", false);
        }
        return record;
    }

    private LedgerProofRecord parse(byte[] payload) {
        try {
            LedgerProofRecord record = mapper.readValue(payload, LedgerProofRecord.class);
            if (record.proofId() == null || record.txId() == null || record.txId().isBlank()) {
                throw new ProofLedgerException("FABRIC_RESPONSE_INVALID", "Bản ghi chaincode thiếu proofId/txId", false);
            }
            return record;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ProofLedgerException("FABRIC_RESPONSE_INVALID", "Chaincode trả dữ liệu không đúng contract", false);
        } catch (java.io.IOException exception) {
            throw new ProofLedgerException("FABRIC_RESPONSE_INVALID", "Không đọc được phản hồi chaincode", false);
        }
    }

    private ProofLedgerReceipt receipt(String transactionId, OptionalLong blockNumber) {
        String blockReference = blockNumber.isPresent()
                ? ProofReceiptFormat.fabricBlockReference(client.channelName(), blockNumber.getAsLong())
                : null;
        return new ProofLedgerReceipt(transactionId, blockReference);
    }
}
