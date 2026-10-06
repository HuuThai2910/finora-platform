package com.finora.loan.service.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.domain.collection.CollectionCaseStatus;
import com.finora.loan.domain.collection.LoanCollectionCase;
import com.finora.loan.domain.servicing.FinoraLoan;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import com.finora.loan.domain.servicing.LoanServicingProjection;
import com.finora.loan.domain.servicing.LoanServicingSnapshot;
import com.finora.loan.repository.collection.LoanCollectionCaseRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CollectionCaseServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Mock private LoanCollectionCaseRepository cases;
    private CollectionCaseService service;
    private FinoraLoan loan;
    private LoanServicingProjection projection;

    @BeforeEach
    void setUp() {
        service = new CollectionCaseService(cases);
        loan = FinoraLoan.activate("LN-001", 9L, "APP-001", "LC-001", "borrower-1",
                88L, money("10000000"), "VND", NOW.minusSeconds(86400));
        ReflectionTestUtils.setField(loan, "id", 1L);
        projection = LoanServicingProjection.fromContractSnapshot(
                1L, money("10000000"), money("1000000"), money("0"), money("0"),
                money("11000000"), LocalDate.of(2026, 9, 21), money("1000000"),
                LocalDate.of(2027, 9, 21), NOW.minusSeconds(86400));
    }

    @Test
    void opensCaseAndMarksLoanDefaultedAtNinetyOneDpd() {
        projection.applyCoreSnapshot(snapshot(91, "1200000", "9000000"), NOW);
        when(cases.findByLoanIdAndStatusForUpdate(1L, CollectionCaseStatus.OPEN))
                .thenReturn(Optional.empty());
        when(cases.save(any(LoanCollectionCase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.apply(loan, projection, NOW);

        assertThat(loan.getStatus()).isEqualTo(FinoraLoanStatus.DEFAULTED);
        verify(cases).save(any(LoanCollectionCase.class));
    }

    @Test
    void closesEpisodeAndCuresDefaultedLoanAfterArrearsArePaid() {
        loan.markDefaulted(NOW.minusSeconds(60));
        LoanCollectionCase current = LoanCollectionCase.open(
                1L, "LN-001", "borrower-1", 91, money("1200000"), money("9000000"),
                LocalDate.of(2026, 7, 5), NOW.minusSeconds(60));
        projection.applyCoreSnapshot(snapshot(0, "0", "8000000"), NOW);
        when(cases.findByLoanIdAndStatusForUpdate(1L, CollectionCaseStatus.OPEN))
                .thenReturn(Optional.of(current));

        service.apply(loan, projection, NOW);

        assertThat(current.getStatus()).isEqualTo(CollectionCaseStatus.CURED);
        assertThat(loan.getStatus()).isEqualTo(FinoraLoanStatus.ACTIVE);
    }

    private static LoanServicingSnapshot snapshot(int dpd, String overdue, String outstanding) {
        return new LoanServicingSnapshot(88L, "LC-001", "ACTIVE", money("10000000"), money("1000000"),
                money("9000000"), money("1000000"), money("100000"), money("900000"),
                money("0"), money("0"), money(outstanding), money(overdue),
                dpd == 0 ? null : LocalDate.of(2026, 7, 5), dpd,
                LocalDate.of(2026, 10, 21), money("1000000"), LocalDate.of(2027, 9, 21));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
