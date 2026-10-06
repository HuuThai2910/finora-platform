package com.finora.loan.service.servicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.domain.messaging.ProcessedEvent;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import com.finora.loan.messaging.event.LoanDelinquencyChangedEventData;
import com.finora.loan.messaging.event.RepaymentDistributedEventData;
import com.finora.loan.repository.messaging.ProcessedEventRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.repository.servicing.RepaymentEventQuarantineRepository;
import com.finora.loan.service.collection.CollectionCaseService;
import com.finora.loan.service.outbox.OutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RepaymentDistributedHandlerTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private FinoraLoanRepository loans;
    @Mock private LoanServicingProjectionRepository projections;
    @Mock private ProcessedEventRepository processed;
    @Mock private OutboxService outbox;
    @Mock private CollectionCaseService collectionCases;
    @Mock private RepaymentEventQuarantineRepository quarantine;

    private RepaymentDistributedHandler handler;
    private FinoraLoan loan;
    private LoanServicingProjection projection;

    @BeforeEach
    void setUp() {
        handler = new RepaymentDistributedHandler(
                loans, projections, processed, outbox, collectionCases, quarantine,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        loan = FinoraLoan.activate("LN-001", 9L, "APP-001", "LC-001", "borrower-1",
                88L, money("10000000"), "VND", NOW.minusSeconds(86400));
        ReflectionTestUtils.setField(loan, "id", 1L);
        projection = LoanServicingProjection.fromContractSnapshot(
                1L, money("10000000"), money("1000000"), money("0"), money("0"),
                money("11000000"), LocalDate.of(2026, 9, 21), money("1000000"),
                LocalDate.of(2027, 9, 21), NOW.minusSeconds(86400));
        projection.applyCoreSnapshot(overdueSnapshot(), NOW.minusSeconds(60));
        when(loans.findByLoanApplicationIdForUpdate(9L)).thenReturn(Optional.of(loan));
        org.mockito.Mockito.lenient().when(projections.findByFinoraLoanIdForUpdate(1L))
                .thenReturn(Optional.of(projection));
        when(quarantine.findByEventIdForUpdate(any())).thenReturn(Optional.empty());
    }

    @Test
    void traDuQuaHanPhatEventDuaDpdVeKhongChoCic() {
        handler.handle(EVENT_ID, NOW, repayment("0.00"));

        ArgumentCaptor<LoanDelinquencyChangedEventData> event =
                ArgumentCaptor.forClass(LoanDelinquencyChangedEventData.class);
        verify(outbox).recordForPublication(eq("FinoraLoan"), eq("1"),
                eq("LoanDelinquencyChanged"), eq(1), event.capture());
        assertThat(event.getValue().previousDaysPastDue()).isEqualTo(12);
        assertThat(event.getValue().daysPastDue()).isZero();
        assertThat(event.getValue().previousDebtGroup()).isEqualTo(2);
        assertThat(event.getValue().debtGroup()).isEqualTo(1);
        assertThat(event.getValue().overdueAmount()).isEqualTo("0.00");
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void repaymentKhongDoiDpdThiKhongPhatEventQuaHan() {
        projection.applyCoreSnapshot(currentSnapshot(), NOW.minusSeconds(30));

        handler.handle(EVENT_ID, NOW, repayment("0.00"));

        verify(outbox, never()).recordForPublication(any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void coreHetDuNoThiDongKhoanVayVaPhatLoanSettled() {
        handler.handle(EVENT_ID, NOW, settledRepayment());

        assertThat(loan.getStatus()).isEqualTo(FinoraLoanStatus.SETTLED);
        verify(outbox).recordForPublication(eq("FinoraLoan"), eq("1"),
                eq("LoanSettled"), eq(1), any());
    }

    @Test
    void eventHopLeDenTruocMappingDuocGiuLaiThayViLamKetKafkaConsumer() {
        when(loans.findByLoanApplicationIdForUpdate(9L)).thenReturn(Optional.empty());

        handler.handle(EVENT_ID, NOW, repayment("0.00"));

        verify(quarantine).save(any(com.finora.loan.domain.servicing.RepaymentEventQuarantine.class));
        verify(processed, never()).save(any());
        verify(projections, never()).findByFinoraLoanIdForUpdate(any());
    }

    private static LoanServicingSnapshot overdueSnapshot() {
        return new LoanServicingSnapshot(
                88L, "LC-001", "ACTIVE", money("10000000"), money("0"), money("10000000"),
                money("1000000"), money("0"), money("1000000"), money("0"), money("0"),
                money("11000000"), money("1000000"), LocalDate.of(2026, 9, 21), 12,
                LocalDate.of(2026, 10, 21), money("1000000"), LocalDate.of(2027, 9, 21));
    }

    private static LoanServicingSnapshot currentSnapshot() {
        return new LoanServicingSnapshot(
                88L, "LC-001", "ACTIVE", money("10000000"), money("0"), money("10000000"),
                money("1000000"), money("0"), money("1000000"), money("0"), money("0"),
                money("11000000"), money("0"), null, 0,
                LocalDate.of(2026, 10, 21), money("1000000"), LocalDate.of(2027, 9, 21));
    }

    private static RepaymentDistributedEventData repayment(String overdueAmount) {
        return new RepaymentDistributedEventData(
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "FINORA-REPAY-1",
                "SCHEDULED", null, "0.00", 9L, 88L, 123L,
                "1000000.00", "800000.00", "200000.00", "0.00", "0.00",
                "9200000.00", "800000.00", "0.00", "0.00", "10000000.00", overdueAmount,
                "VND", LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 21),
                "1000000.00", NOW, List.of());
    }

    private static RepaymentDistributedEventData settledRepayment() {
        return new RepaymentDistributedEventData(
                UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"), "FINORA-REPAY-2",
                "EARLY_SETTLEMENT", "99999999-8888-7777-6666-555555555555", "100000.00",
                9L, 88L, 124L, "11100000.00", "10000000.00", "1000000.00", "0.00", "0.00",
                "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "VND",
                LocalDate.of(2026, 10, 3), null, "0.00", NOW, List.of());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
