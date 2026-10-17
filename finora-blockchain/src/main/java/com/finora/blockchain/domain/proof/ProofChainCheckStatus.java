package com.finora.blockchain.domain.proof;

/**
 * Kết quả đối chiếu gần nhất giữa bản ghi PostgreSQL và bản ghi trên Fabric (F08 reconciliation). Sai lệch chỉ được ghi
 * nhận và cảnh báo; Blockchain không tự sửa dữ liệu ở bất kỳ phía nào.
 */
public enum ProofChainCheckStatus {
    MATCHED,
    MISMATCHED,
    MISSING_ON_CHAIN
}
