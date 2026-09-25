package com.finora.blockchain.service.proof;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.integration.proof.ProofLedger;
import com.finora.blockchain.integration.proof.ProofLedgerCommand;
import com.finora.blockchain.integration.proof.ProofLedgerException;
import com.finora.blockchain.integration.proof.ProofLedgerReceipt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProofSubmissionProcessorTest {

    @Mock
    private ProofSubmissionStateService stateService;
    @Mock
    private ProofLedger proofLedger;

    private ProofSubmissionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ProofSubmissionProcessor(stateService, proofLedger);
    }

    @Test
    void callsProviderOutsideStateServiceAndConfirmsReceipt() {
        Long id = 12L;
        UUID token = UUID.randomUUID();
        ProofLedgerCommand command = command();
        ProofLedgerReceipt receipt = new ProofLedgerReceipt("MOCK-tx", "MOCK-block");
        when(stateService.claim(id)).thenReturn(new ClaimedProofSubmission(id, token, command));
        when(proofLedger.submit(command)).thenReturn(receipt);

        processor.process(id);

        verify(stateService).markConfirmed(id, token, receipt);
    }

    @Test
    void preservesProviderRetryabilityWhenMarkingFailure() {
        Long id = 13L;
        UUID token = UUID.randomUUID();
        ProofLedgerCommand command = command();
        when(stateService.claim(id)).thenReturn(new ClaimedProofSubmission(id, token, command));
        when(proofLedger.submit(command)).thenThrow(
                new ProofLedgerException("FABRIC_DOWN", "gateway unavailable", true));

        processor.process(id);

        verify(stateService).markFailed(id, token, "FABRIC_DOWN", "gateway unavailable", true);
    }

    private static ProofLedgerCommand command() {
        return new ProofLedgerCommand(
                UUID.randomUUID(), "LOAN_CONTRACT", "LC-001", ProofType.CONTRACT_DOCUMENT, "a".repeat(64), 1);
    }
}
