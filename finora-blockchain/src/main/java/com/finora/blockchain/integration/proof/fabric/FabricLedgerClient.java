package com.finora.blockchain.integration.proof.fabric;

import java.util.OptionalLong;

/**
 * Lời gọi chaincode finora-ledger qua Fabric Gateway. Tách khỏi {@link FabricProofLedger} để logic đối chiếu trước khi
 * ghi kiểm thử được không cần mạng; mọi lỗi được đổi sẵn thành
 * {@link com.finora.blockchain.integration.proof.ProofLedgerException} có cờ retryable.
 */
public interface FabricLedgerClient {

    /** Chạy hàm chỉ đọc trên peer, không gửi orderer. Payload rỗng nghĩa là chaincode trả "không có". */
    byte[] evaluate(String function, String... arguments);

    /** Endorse trên hai tổ chức, gửi orderer và chờ commit; chỉ trả về khi giao dịch hợp lệ trong block. */
    FabricCommit submit(String function, String... arguments);

    /** Số block chứa giao dịch (qscc GetBlockByTxID); rỗng nếu không tra được, receipt vẫn hợp lệ. */
    OptionalLong blockNumberOf(String transactionId);

    String channelName();

    record FabricCommit(byte[] result, String transactionId, long blockNumber) {
    }
}
