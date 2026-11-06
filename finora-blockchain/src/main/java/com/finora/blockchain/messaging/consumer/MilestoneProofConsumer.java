package com.finora.blockchain.messaging.consumer;

import com.finora.blockchain.messaging.event.ProofMilestone;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer group {@code blockchain-group} cho bốn mốc proof và giấy xác nhận chuyển nhượng Note. Chỉ đăng ký proof
 * trong DB của Blockchain (transaction ngắn, không gọi Fabric); worker ghi ledger sau. Fabric tắt vì vậy không chặn hay rollback luồng tiền/hợp đồng nguồn.
 */
@Component
@RequiredArgsConstructor
public class MilestoneProofConsumer {

    private final MilestoneProofRegistrar milestoneProofRegistrar;

    @KafkaListener(topics = "${finora.blockchain.contract-activated-topic:finora.loan.contract-activated}")
    public void contractActivated(ConsumerRecord<String, String> record) {
        milestoneProofRegistrar.register(ProofMilestone.CONTRACT_SIGNED, record);
    }

    @KafkaListener(topics = "${finora.blockchain.loan-disbursed-topic:finora.loan.disbursed}")
    public void loanDisbursed(ConsumerRecord<String, String> record) {
        milestoneProofRegistrar.register(ProofMilestone.LOAN_DISBURSED, record);
    }

    @KafkaListener(topics = "${finora.blockchain.repayment-distributed-topic:finora.payment.repayment-distributed}")
    public void repaymentDistributed(ConsumerRecord<String, String> record) {
        milestoneProofRegistrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED, record);
    }

    @KafkaListener(topics = "${finora.blockchain.note-ownership-changed-topic:finora.investment.note-ownership-changed}")
    public void noteOwnershipChanged(ConsumerRecord<String, String> record) {
        milestoneProofRegistrar.register(ProofMilestone.NOTE_OWNERSHIP_CHANGED, record);
    }

    @KafkaListener(topics = "${finora.blockchain.note-transfer-document-issued-topic:finora.investment.note-transfer-document-issued}")
    public void noteTransferDocumentIssued(ConsumerRecord<String, String> record) {
        milestoneProofRegistrar.register(ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED, record);
    }
}
