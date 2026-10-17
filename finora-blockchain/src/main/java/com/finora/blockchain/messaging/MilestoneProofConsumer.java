package com.finora.blockchain.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code blockchain-group} cho bốn mốc proof. Chỉ đăng ký proof trong DB của Blockchain (transaction
 * ngắn, không gọi Fabric); worker ghi ledger sau. Fabric tắt vì vậy không chặn hay rollback luồng tiền/hợp đồng nguồn.
 */
@Component
@RequiredArgsConstructor
public class MilestoneProofConsumer {

    private static final String EVENT_ID = "finora-event-id";
    private static final String EVENT_TYPE = "finora-event-type";
    private static final String EVENT_VERSION = "finora-event-version";

    private final MilestoneProofRegistrar registrar;

    @KafkaListener(topics = "${finora.blockchain.contract-activated-topic:finora.loan.contract-activated}")
    public void contractActivated(@Payload String payload,
            @Header(name = EVENT_ID, required = false) String id,
            @Header(name = EVENT_TYPE, required = false) String type,
            @Header(name = EVENT_VERSION, required = false) String version) {
        registrar.register(ProofMilestone.CONTRACT_SIGNED, payload, id, type, version);
    }

    @KafkaListener(topics = "${finora.blockchain.loan-disbursed-topic:finora.loan.disbursed}")
    public void loanDisbursed(@Payload String payload,
            @Header(name = EVENT_ID, required = false) String id,
            @Header(name = EVENT_TYPE, required = false) String type,
            @Header(name = EVENT_VERSION, required = false) String version) {
        registrar.register(ProofMilestone.LOAN_DISBURSED, payload, id, type, version);
    }

    @KafkaListener(topics = "${finora.blockchain.repayment-distributed-topic:finora.payment.repayment-distributed}")
    public void repaymentDistributed(@Payload String payload,
            @Header(name = EVENT_ID, required = false) String id,
            @Header(name = EVENT_TYPE, required = false) String type,
            @Header(name = EVENT_VERSION, required = false) String version) {
        registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED, payload, id, type, version);
    }

    @KafkaListener(topics = "${finora.blockchain.note-ownership-changed-topic:finora.investment.note-ownership-changed}")
    public void noteOwnershipChanged(@Payload String payload,
            @Header(name = EVENT_ID, required = false) String id,
            @Header(name = EVENT_TYPE, required = false) String type,
            @Header(name = EVENT_VERSION, required = false) String version) {
        registrar.register(ProofMilestone.NOTE_OWNERSHIP_CHANGED, payload, id, type, version);
    }
}
