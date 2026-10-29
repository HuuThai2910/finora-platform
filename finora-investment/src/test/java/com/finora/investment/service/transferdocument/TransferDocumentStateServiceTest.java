package com.finora.investment.service.transferdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finora.investment.config.TransferDocumentProperties;
import com.finora.investment.domain.transferdocument.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferDocumentStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");

    private NoteTransferDocumentRepository documents;
    private InvestmentOutboxService outbox;
    private TransferDocumentStateService service;

    @BeforeEach
    void setUp() {
        documents = mock(NoteTransferDocumentRepository.class);
        outbox = mock(InvestmentOutboxService.class);
        TransferDocumentProperties properties = new TransferDocumentProperties(true, null, 20, 3,
                Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(2), null);
        service = new TransferDocumentStateService(documents, mock(BookTradeRepository.class),
                mock(MarketListingRepository.class), outbox, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void backoffDoublesUpToMaximum() {
        assertThat(service.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(service.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(service.backoff(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(service.backoff(10)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void claimPushesNextAttemptPastLeaseAndCountsAttempt() {
        NoteTransferDocument document = pending();
        when(documents.findByIdForUpdate(1L)).thenReturn(Optional.of(document));

        assertThat(service.claim(1L)).contains(9L);
        assertThat(document.getAttempts()).isEqualTo(1);
        assertThat(document.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        // Lượt thứ hai trong lúc lease còn hiệu lực không nhận được.
        assertThat(service.claim(1L)).isEmpty();
    }

    @Test
    void failRetriesThenStopsAfterMaxAttempts() {
        NoteTransferDocument document = pending();
        when(documents.findByIdForUpdate(1L)).thenReturn(Optional.of(document));

        document.claim(Duration.ZERO, NOW);
        service.fail(1L, "USER_IDENTITY_UNAVAILABLE");
        assertThat(document.getStatus()).isEqualTo(NoteTransferDocumentStatus.PENDING);
        assertThat(document.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));

        document.claim(Duration.ZERO, NOW);
        document.claim(Duration.ZERO, NOW);
        service.fail(1L, "USER_IDENTITY_UNAVAILABLE");
        assertThat(document.getStatus()).isEqualTo(NoteTransferDocumentStatus.FAILED);
        assertThat(document.getLastError()).isEqualTo("USER_IDENTITY_UNAVAILABLE");
    }

    @Test
    void completeStoresPdfAndRecordsAnchorEventOnce() {
        NoteTransferDocument document = pending();
        when(documents.findByIdForUpdate(1L)).thenReturn(Optional.of(document));
        ObjectNode payload = new ObjectMapper().createObjectNode().put("documentHash", "ab".repeat(32));

        service.complete(1L, new byte[] {1, 2, 3}, "ab".repeat(32), payload, "CN=FINORA", NOW);
        service.complete(1L, new byte[] {9}, "cd".repeat(32), payload, "CN=FINORA", NOW);

        assertThat(document.isIssued()).isTrue();
        assertThat(document.contentCopy()).containsExactly(1, 2, 3);
        assertThat(document.getProofPayload()).contains("ab".repeat(32));
        verify(outbox).record(eq("LOAN"), eq("1001"), eq("NoteTransferDocumentIssued"), eq(1), any());
    }

    @Test
    void reopenOnlyFailedDocuments() {
        NoteTransferDocument document = pending();
        when(documents.findByTradeReference("TRD-1")).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.reopen("TRD-1")).isInstanceOf(InvestmentDomainException.class);

        document.markFailed("X", NOW);
        service.reopen("TRD-1");
        assertThat(document.getStatus()).isEqualTo(NoteTransferDocumentStatus.PENDING);
        assertThat(document.getAttempts()).isZero();
        verify(outbox, never()).record(any(), any(), any(), any(Integer.class), any());
    }

    private static NoteTransferDocument pending() {
        return NoteTransferDocument.pending(9L, "TRD-1", 1001L, 55L, NOW);
    }
}
