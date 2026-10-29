package com.finora.blockchain.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofType;
import com.finora.blockchain.service.proof.ProofRegistrationCommand;
import com.finora.blockchain.service.proof.ProofSubmissionService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MilestoneProofRegistrarTest {

    private static final String CONTRACT_DATA = "{\"loanApplicationId\":123,\"applicationNumber\":\"LA-2026-000123\","
            + "\"contractNumber\":\"LC-2026-000123\",\"listingId\":456,"
            + "\"documentHash\":\"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4\","
            + "\"pdfReceiptHash\":\"2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae\","
            + "\"allocationVersion\":1,"
            + "\"allocationHash\":\"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9\","
            + "\"activatedAt\":\"2026-10-17T03:00:00Z\"}";

    private static final String PDF_HASH = "9b2d3f6c1e0a4b5c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c";

    @Mock
    private ProofSubmissionService proofs;

    private MilestoneProofRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new MilestoneProofRegistrar(new ObjectMapper(), proofs);
    }

    @Test
    void contractActivationAnchorsTheCanonicalHashOfEventData() {
        UUID eventId = UUID.randomUUID();

        registrar.register(ProofMilestone.CONTRACT_SIGNED, envelope(eventId, CONTRACT_DATA), eventId.toString(),
                "LoanContractActivated", "1");

        ProofRegistrationCommand command = captured();
        assertThat(command.sourceService()).isEqualTo("finora-loan");
        assertThat(command.sourceEventType()).isEqualTo("LoanContractActivated");
        assertThat(command.sourceEventVersion()).isEqualTo(1);
        assertThat(command.sourceOccurredAt()).isEqualTo(Instant.parse("2026-10-17T03:00:01Z"));
        assertThat(command.proofType()).isEqualTo(ProofType.CONTRACT_DOCUMENT);
        assertThat(command.aggregateType()).isEqualTo("LoanContract");
        assertThat(command.aggregateId()).isEqualTo("LC-2026-000123");
        assertThat(command.loanApplicationId()).isEqualTo(123L);
        // Cùng vector với ProofPayloadHasherTest (tính độc lập bằng Python).
        assertThat(command.payloadHash()).isEqualTo("efee2119b4714f9547d8781865a70ae390580f1a067766fd50ccb846e692a729");
        assertThat(command.payloadVersion()).isEqualTo(2);
    }

    @Test
    void disbursementUsesTheSagaAsMilestone() {
        UUID eventId = UUID.randomUUID();
        String sagaId = UUID.randomUUID().toString();
        String data = "{\"sagaId\":\"" + sagaId + "\",\"loanApplicationId\":123,\"applicationNumber\":\"LA-1\","
                + "\"contractNumber\":\"LC-1\",\"listingId\":456,\"amount\":\"50000000.00\",\"currency\":\"VND\","
                + "\"paymentReference\":\"MOCK-" + sagaId + "\",\"fineractLoanId\":77,\"disbursedAt\":\"2026-10-17T04:00:00Z\","
                + "\"maturityDate\":null}";

        registrar.register(ProofMilestone.LOAN_DISBURSED, envelope(eventId, data), null, "LoanDisbursed", null);

        ProofRegistrationCommand command = captured();
        assertThat(command.proofType()).isEqualTo(ProofType.DISBURSEMENT);
        assertThat(command.aggregateType()).isEqualTo("DisbursementSaga");
        assertThat(command.aggregateId()).isEqualTo(sagaId);
    }

    @Test
    void repaymentKeepsTheRepaymentAggregateOfBc001() {
        UUID eventId = UUID.randomUUID();

        registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                envelope(eventId, "{\"repaymentId\":\"REPAY-10\",\"loanApplicationId\":9,\"amount\":\"1000000.00\"}"),
                eventId.toString(), "RepaymentDistributed", "1");

        ProofRegistrationCommand command = captured();
        assertThat(command.sourceService()).isEqualTo("finora-payment");
        assertThat(command.aggregateType()).isEqualTo("Repayment");
        assertThat(command.aggregateId()).isEqualTo("REPAY-10");
        assertThat(command.payloadHash()).hasSize(64);
    }

    @Test
    void noteIssuanceIsAnchoredPerListingAndTransferPerTrade() {
        UUID issued = UUID.randomUUID();
        registrar.register(ProofMilestone.NOTE_OWNERSHIP_CHANGED, envelope(issued, noteData("ISSUED", "MOCK-ref")),
                issued.toString(), "NoteOwnershipChanged", "1");
        ProofRegistrationCommand issuance = captured();
        assertThat(issuance.aggregateType()).isEqualTo("NoteIssuance");
        assertThat(issuance.aggregateId()).isEqualTo("456");
        assertThat(issuance.proofType()).isEqualTo(ProofType.NOTE_OWNERSHIP);

        UUID transferred = UUID.randomUUID();
        registrar.register(ProofMilestone.NOTE_OWNERSHIP_CHANGED,
                envelope(transferred, noteData("TRANSFERRED", "TRD-ABCDEF0123456789ABCD")), null, null, null);
        ArgumentCaptor<ProofRegistrationCommand> commands = ArgumentCaptor.forClass(ProofRegistrationCommand.class);
        verify(proofs, org.mockito.Mockito.times(2)).register(commands.capture());
        assertThat(commands.getValue().aggregateType()).isEqualTo("NoteTransfer");
        assertThat(commands.getValue().aggregateId()).isEqualTo("TRD-ABCDEF0123456789ABCD");
    }

    @Test
    void noteTransferDocumentIsAnchoredPerTradeWithTheCanonicalHashOfEventData() {
        UUID eventId = UUID.randomUUID();

        registrar.register(ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED,
                envelope(eventId, transferDocumentData("\"" + PDF_HASH + "\"", "\"SHA-256\"")), eventId.toString(),
                "NoteTransferDocumentIssued", "1");

        ProofRegistrationCommand command = captured();
        assertThat(command.sourceService()).isEqualTo("finora-investment");
        assertThat(command.sourceEventType()).isEqualTo("NoteTransferDocumentIssued");
        assertThat(command.sourceEventVersion()).isEqualTo(1);
        assertThat(command.proofType()).isEqualTo(ProofType.NOTE_TRANSFER_DOCUMENT);
        assertThat(command.aggregateType()).isEqualTo("NoteTransferDocument");
        assertThat(command.aggregateId()).isEqualTo("TRD-ABCDEF0123456789ABCD");
        assertThat(command.loanApplicationId()).isEqualTo(123L);
        // Neo hash canonical của data (chứa documentHash của PDF), không phải documentHash trực tiếp.
        assertThat(command.payloadHash()).hasSize(64).isNotEqualTo(PDF_HASH);
        assertThat(command.payloadVersion()).isEqualTo(2);
    }

    @Test
    void noteTransferDocumentWithoutAValidPdfHashGoesToDeadLetter() {
        UUID eventId = UUID.randomUUID();
        String missingHash = "{\"documentNumber\":\"CN-TRD-0A1B2C3D\",\"tradeReference\":\"TRD-ABCDEF0123456789ABCD\","
                + "\"loanApplicationId\":123,\"listingId\":45,\"hashAlgorithm\":\"SHA-256\"}";
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(
                ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED, envelope(eventId, missingHash), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(
                ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED,
                envelope(eventId, transferDocumentData("\"abc123\"", "\"SHA-256\"")), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(
                ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED,
                envelope(eventId, transferDocumentData("\"" + PDF_HASH.toUpperCase() + "\"", "\"SHA-256\"")),
                null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(
                ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED,
                envelope(eventId, transferDocumentData("\"" + PDF_HASH + "\"", "\"MD5\"")), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(
                ProofMilestone.NOTE_TRANSFER_DOCUMENT_ISSUED,
                envelope(eventId, transferDocumentData("\"" + PDF_HASH + "\"", "\"SHA-256\"")), null,
                "NoteOwnershipChanged", "1"));
        verify(proofs, never()).register(any());
    }

    @Test
    void headerEnvelopeMismatchAndMissingBusinessFieldsGoToDeadLetter() {
        UUID eventId = UUID.randomUUID();
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(ProofMilestone.CONTRACT_SIGNED,
                envelope(eventId, CONTRACT_DATA), UUID.randomUUID().toString(), "LoanContractActivated", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(ProofMilestone.CONTRACT_SIGNED,
                envelope(eventId, CONTRACT_DATA), eventId.toString(), "LoanDisbursed", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(ProofMilestone.CONTRACT_SIGNED,
                envelope(eventId, "{\"contractNumber\":\"LC-1\"}"), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(ProofMilestone.NOTE_OWNERSHIP_CHANGED,
                envelope(eventId, noteData("BURNED", "X")), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                envelope(eventId, "{\"repaymentId\":\"R-1\",\"amount\":1000000.5}"), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> registrar.register(ProofMilestone.REPAYMENT_DISTRIBUTED,
                "{not json", null, null, null));
        verify(proofs, never()).register(any());
    }

    private ProofRegistrationCommand captured() {
        ArgumentCaptor<ProofRegistrationCommand> command = ArgumentCaptor.forClass(ProofRegistrationCommand.class);
        verify(proofs).register(command.capture());
        return command.getValue();
    }

    private static String envelope(UUID eventId, String data) {
        return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-10-17T03:00:01Z\",\"version\":1,\"data\":" + data + "}";
    }

    private static String transferDocumentData(String documentHash, String hashAlgorithm) {
        return "{\"documentNumber\":\"CN-TRD-0A1B2C3D\",\"tradeReference\":\"TRD-ABCDEF0123456789ABCD\","
                + "\"loanApplicationId\":123,\"listingId\":45,\"documentHash\":" + documentHash + ","
                + "\"hashAlgorithm\":" + hashAlgorithm + ",\"issuedAt\":\"2026-10-29T03:00:00Z\"}";
    }

    private static String noteData(String reason, String reference) {
        return "{\"loanApplicationId\":123,\"listingId\":456,\"currency\":\"VND\",\"reason\":\"" + reason + "\","
                + "\"reference\":\"" + reference + "\",\"changedAt\":\"2026-10-17T04:00:00Z\","
                + "\"notes\":[{\"noteId\":1,\"noteNumber\":\"N-1\",\"investorId\":\"42\",\"outstandingPrincipal\":\"100000.00\"}]}";
    }
}
