package com.finora.blockchain.integration.proof;

/**
 * Fail-closed cho tới khi network profile, chaincode và contract P4 được hai owner chốt.
 * Không được thay bằng receipt giả khi operator đã chọn Fabric.
 */
public final class HyperledgerFabricProofLedger implements ProofLedger {

    @Override
    public ProofLedgerReceipt submit(ProofLedgerCommand command) {
        throw new ProofLedgerException(
                "FABRIC_INTEGRATION_NOT_READY",
                "Hyperledger Fabric chưa được kích hoạt vì contract P4 chưa đạt phase gate",
                false
        );
    }
}
