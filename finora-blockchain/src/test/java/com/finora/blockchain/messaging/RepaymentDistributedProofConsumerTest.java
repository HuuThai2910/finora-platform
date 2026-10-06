package com.finora.blockchain.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepaymentDistributedProofConsumerTest {
    @Mock ProofSubmissionService proofs;

    @Test
    void registersOnlyHashAndSourceIdentity() {
        UUID eventId = UUID.randomUUID();
        var consumer = new RepaymentDistributedProofConsumer(new ObjectMapper(), proofs);
        String payload = "{\"eventId\":\"" + eventId + "\",\"version\":1,"
                + "\"data\":{\"repaymentId\":\"REPAY-10\",\"amount\":\"1000000.00\"}}";

        consumer.consume(payload, eventId.toString(), "RepaymentDistributed", "1");

        ArgumentCaptor<ProofRegistrationCommand> command =
                ArgumentCaptor.forClass(ProofRegistrationCommand.class);
        verify(proofs).register(command.capture());
        assertThat(command.getValue().sourceService()).isEqualTo("finora-payment");
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().aggregateId()).isEqualTo("REPAY-10");
        assertThat(command.getValue().proofType()).isEqualTo(ProofType.REPAYMENT);
        assertThat(command.getValue().payloadHash()).hasSize(64);
    }

    @Test
    void rejectsHeaderEnvelopeMismatch() {
        UUID eventId = UUID.randomUUID();
        var consumer = new RepaymentDistributedProofConsumer(new ObjectMapper(), proofs);
        String payload = "{\"eventId\":\"" + eventId + "\",\"version\":1,"
                + "\"data\":{\"repaymentId\":\"REPAY-10\"}}";

        assertThatThrownBy(() -> consumer.consume(payload, UUID.randomUUID().toString(),
                "RepaymentDistributed", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope/header");
    }
}
