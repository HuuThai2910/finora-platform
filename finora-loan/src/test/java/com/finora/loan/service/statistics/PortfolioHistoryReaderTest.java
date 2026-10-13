package com.finora.loan.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.domain.servicing.LoanServicingHistory;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.client.FineractServicingGateway;
import com.finora.loan.repository.statistics.LoanStatisticsRepository.HistoryLoanRow;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PortfolioHistoryReaderTest {

    private static final LoanServicingHistory HISTORY = new LoanServicingHistory("LC-1", List.of(), List.of());

    @Mock
    private FineractServicingGateway gateway;

    private MutableClock clock;
    private PortfolioHistoryReader reader;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-13T01:00:00Z"));
        reader = new PortfolioHistoryReader(gateway, clock);
    }

    @Test
    void readsOnceAndReadsAgainOnlyWhenTheProjectionFingerprintChanges() {
        when(gateway.readHistory(101L)).thenReturn(HISTORY);

        assertThat(reader.read(List.of(row("v1")))).containsEntry(1L, HISTORY);
        assertThat(reader.read(List.of(row("v1")))).containsEntry(1L, HISTORY);
        verify(gateway, times(1)).readHistory(101L);

        reader.read(List.of(row("v2")));
        verify(gateway, times(2)).readHistory(101L);
    }

    @Test
    void dropsHistoryWhoseExternalIdBelongsToAnotherLoan() {
        when(gateway.readHistory(101L)).thenReturn(new LoanServicingHistory("LC-OTHER", List.of(), List.of()));

        assertThat(reader.read(List.of(row("v1")))).isEmpty();
    }

    @Test
    void remembersFailuresBrieflyThenRetries() {
        when(gateway.readHistory(101L))
                .thenThrow(new FineractIntegrationException("FINERACT_REQUEST_REJECTED", "404", false, null))
                .thenReturn(HISTORY);

        assertThat(reader.read(List.of(row("v1")))).isEmpty();
        assertThat(reader.read(List.of(row("v1")))).isEmpty();
        verify(gateway, times(1)).readHistory(101L);

        clock.advance(PortfolioHistoryReader.FAILURE_RETRY);
        assertThat(reader.read(List.of(row("v1")))).containsEntry(1L, HISTORY);
        verify(gateway, times(2)).readHistory(101L);
    }

    private static HistoryLoanRow row(String fingerprint) {
        return new HistoryLoanRow(1L, 101L, "LC-1", fingerprint);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(java.time.Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
