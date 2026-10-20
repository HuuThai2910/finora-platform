package com.finora.blockchain.service.proof;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.blockchain.domain.proof.ProofProviderType;
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

    private static final String FABRIC_TX = "c".repeat(64);

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
        ProofLedgerReceipt receipt = new ProofLedgerReceipt(FABRIC_TX, "finora-channel/4");
        when(stateService.claim(id)).thenReturn(claimed(id, token, ProofProviderType.HYPERLEDGER_FABRIC, command));
        when(proofLedger.provider()).thenReturn(ProofProviderType.HYPERLEDGER_FABRIC);
        when(proofLedger.submit(command)).thenReturn(receipt);

        processor.process(id);

        verify(stateService).markConfirmed(id, token, receipt);
    }

    @Test
    void preservesProviderRetryabilityWhenMarkingFailure() {
        Long id = 13L;
        UUID token = UUID.randomUUID();
        ProofLedgerCommand command = command();
        when(stateService.claim(id)).thenReturn(claimed(id, token, ProofProviderType.HYPERLEDGER_FABRIC, command));
        when(proofLedger.provider()).thenReturn(ProofProviderType.HYPERLEDGER_FABRIC);
        when(proofLedger.submit(command)).thenThrow(
                new ProofLedgerException("FABRIC_UNAVAILABLE", "gateway unavailable", true));

        processor.process(id);

        verify(stateService).markFailed(id, token, "FABRIC_UNAVAILABLE", "gateway unavailable", true);
    }

    @Test
    void neverSubmitsAProofRegisteredForAnotherProvider() {
        Long id = 14L;
        UUID token = UUID.randomUUID();
        when(stateService.claim(id)).thenReturn(claimed(id, token, ProofProviderType.HYPERLEDGER_FABRIC, command()));
        when(proofLedger.provider()).thenReturn(ProofProviderType.MOCK);

        processor.process(id);

        verify(proofLedger, never()).submit(any());
        verify(stateService).markFailed(eq(id), eq(token), eq("PROOF_PROVIDER_NOT_ACTIVE"), anyString(), eq(true));
    }

    @Test
    void mockLookingReceiptIsNeverStoredOnAFabricProof() {
        Long id = 15L;
        UUID token = UUID.randomUUID();
        ProofLedgerCommand command = command();
        when(stateService.claim(id)).thenReturn(claimed(id, token, ProofProviderType.HYPERLEDGER_FABRIC, command));
        when(proofLedger.provider()).thenReturn(ProofProviderType.HYPERLEDGER_FABRIC);
        when(proofLedger.submit(command)).thenReturn(new ProofLedgerReceipt("MOCK-1", "MOCK-BLOCK-NOT-ON-CHAIN"));

        processor.process(id);

        verify(stateService, never()).markConfirmed(any(), any(), any());
        verify(stateService).markFailed(eq(id), eq(token), eq("PROOF_RECEIPT_PROVIDER_MISMATCH"), anyString(), eq(false));
    }

    @Test
    void nothingHappensWhenAnotherWorkerOwnsTheClaim() {
        when(stateService.claim(16L)).thenReturn(null);

        processor.process(16L);

        verify(proofLedger, never()).submit(any());
        verify(stateService, never()).markFailed(any(), any(), anyString(), anyString(), anyBoolean());
    }

    private static ClaimedProofSubmission claimed(Long id, UUID token, ProofProviderType provider, ProofLedgerCommand command) {
        return new ClaimedProofSubmission(id, token, provider, command);
    }

    private static ProofLedgerCommand command() {
        return new ProofLedgerCommand(UUID.randomUUID(), "LoanContract", "LC-001", ProofType.CONTRACT_DOCUMENT,
                "a".repeat(64), 2, "finora-loan");
    }
}
