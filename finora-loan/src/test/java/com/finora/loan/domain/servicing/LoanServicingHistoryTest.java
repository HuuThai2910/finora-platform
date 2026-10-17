package com.finora.loan.domain.servicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.loan.domain.servicing.LoanServicingHistory.Installment;
import com.finora.loan.domain.servicing.LoanServicingHistory.PrincipalMovement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoanServicingHistoryTest {

    private static final LocalDate DISBURSED = LocalDate.of(2026, 5, 19);

    /** 1.000 giải ngân 19/5; kỳ 19/6 trả đúng hạn, kỳ 19/7 trả trễ ngày 25/7, kỳ 19/8 chưa trả. */
    private final LoanServicingHistory history = new LoanServicingHistory("LC-1",
            List.of(new PrincipalMovement(DISBURSED, new BigDecimal("1000")),
                    new PrincipalMovement(LocalDate.of(2026, 6, 19), new BigDecimal("-300")),
                    new PrincipalMovement(LocalDate.of(2026, 7, 25), new BigDecimal("-300"))),
            List.of(new Installment(LocalDate.of(2026, 8, 19), null),
                    new Installment(LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 19)),
                    new Installment(LocalDate.of(2026, 7, 19), LocalDate.of(2026, 7, 25))));

    @Test
    void positionIsNullBeforeDisbursement() {
        assertThat(history.positionAt(DISBURSED.minusDays(1))).isNull();
    }

    @Test
    void principalCountsMovementsUpToTheEndOfTheDay() {
        assertThat(history.positionAt(DISBURSED).principalOutstanding()).isEqualByComparingTo("1000");
        assertThat(history.positionAt(LocalDate.of(2026, 6, 19)).principalOutstanding()).isEqualByComparingTo("700");
        assertThat(history.positionAt(LocalDate.of(2026, 7, 25)).principalOutstanding()).isEqualByComparingTo("400");
    }

    @Test
    void installmentDueTodayOrPaidOnDueDateIsNotOverdue() {
        assertThat(history.positionAt(LocalDate.of(2026, 6, 19)).daysPastDue()).isZero();
        assertThat(history.positionAt(LocalDate.of(2026, 6, 20)).daysPastDue()).isZero();
        assertThat(history.positionAt(LocalDate.of(2026, 7, 19)).daysPastDue()).isZero();
    }

    @Test
    void daysPastDueRunUntilTheInstallmentIsFullyPaid() {
        assertThat(history.positionAt(LocalDate.of(2026, 7, 24)).daysPastDue()).isEqualTo(5);
        assertThat(history.positionAt(LocalDate.of(2026, 7, 25)).daysPastDue()).isZero();
        // Kỳ 19/8 chưa trả: tính từ kỳ chưa trả sớm nhất.
        assertThat(history.positionAt(LocalDate.of(2026, 10, 13)).daysPastDue()).isEqualTo(55);
    }

    @Test
    void debtGroupKeepsTheHighestGroupOfTheEpisodeUntilArrearsAreFullyPaid() {
        // Kỳ 17/11, 17/12, 17/1 chưa trả tới 16/2 (DPD 91); 16/2 trả kỳ 17/11 → DPD 61; 1/3 trả nốt hai kỳ.
        LoanServicingHistory partlyCured = new LoanServicingHistory("LC-3",
                List.of(new PrincipalMovement(LocalDate.of(2026, 10, 17), new BigDecimal("10000"))),
                List.of(new Installment(LocalDate.of(2026, 11, 17), LocalDate.of(2027, 2, 16)),
                        new Installment(LocalDate.of(2026, 12, 17), LocalDate.of(2027, 3, 1)),
                        new Installment(LocalDate.of(2027, 1, 17), LocalDate.of(2027, 3, 1)),
                        new Installment(LocalDate.of(2027, 4, 17), null)));

        // Cuối ngày 15/2 mới quá hạn 90 ngày (nhóm 2); sáng 16/2 chạm 91 ngày rồi mới trả bớt.
        assertThat(partlyCured.debtGroupAt(LocalDate.of(2027, 2, 15))).isEqualTo(2);
        // D29: trả bớt chỉ hạ DPD về 61, đợt quá hạn chưa kết thúc nên vẫn nhóm 3.
        assertThat(partlyCured.positionAt(LocalDate.of(2027, 2, 16)).daysPastDue()).isEqualTo(61);
        assertThat(partlyCured.debtGroupAt(LocalDate.of(2027, 2, 16))).isEqualTo(3);
        assertThat(partlyCured.debtGroupAt(LocalDate.of(2027, 2, 28))).isEqualTo(3);
        // Trả đủ phần quá hạn: đợt kết thúc, nhóm về 1; đợt mới sau đó tính lại từ đầu.
        assertThat(partlyCured.debtGroupAt(LocalDate.of(2027, 3, 1))).isEqualTo(1);
        assertThat(partlyCured.debtGroupAt(LocalDate.of(2027, 4, 30))).isEqualTo(2);
    }

    @Test
    void fullyRepaidLoanHasZeroPosition() {
        LoanServicingHistory settled = new LoanServicingHistory("LC-2",
                List.of(new PrincipalMovement(DISBURSED, new BigDecimal("500")),
                        new PrincipalMovement(LocalDate.of(2026, 6, 1), new BigDecimal("-500"))),
                List.of(new Installment(LocalDate.of(2026, 6, 19), LocalDate.of(2026, 6, 1))));

        LoanServicingHistory.Position position = settled.positionAt(LocalDate.of(2026, 9, 1));

        assertThat(position.principalOutstanding()).isEqualByComparingTo("0");
        assertThat(position.daysPastDue()).isZero();
    }
}
