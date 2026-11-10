package com.finora.loan.service.servicing.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.model.LoanServicingHistory;
import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse;
import com.finora.loan.dto.servicing.response.AdminLoanInstallmentsResponse.InstallmentStatus;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import com.finora.loan.repository.servicing.LoanServicingProjectionRepository;
import com.finora.loan.service.statistics.PortfolioHistoryReader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kỳ trả cho thẻ Trả nợ: đã trả theo Fineract, quá hạn/đến hạn/chưa đến hạn theo ngày Việt Nam. */
class AdminLoanInstallmentServiceImplTest {

    /** 2026-11-10 00:30 giờ Việt Nam vẫn là 2026-11-09 theo UTC: ngày nghiệp vụ phải là 10/11. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-11-09T17:30:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    private final FinoraLoanRepository finoraLoanRepository = mock(FinoraLoanRepository.class);
    private final PortfolioHistoryReader portfolioHistoryReader = mock(PortfolioHistoryReader.class);
    private final AdminLoanInstallmentServiceImpl service = new AdminLoanInstallmentServiceImpl(
            finoraLoanRepository, mock(LoanServicingProjectionRepository.class), portfolioHistoryReader, CLOCK);

    @Test
    void installmentsAreClassifiedAgainstTheVietnamBusinessDate() {
        stubLoan();
        BigDecimal due = new BigDecimal("8955000.00");
        when(portfolioHistoryReader.read(anyList())).thenReturn(Map.of(7L, new LoanServicingHistory("LC-1", List.of(),
                List.of(
                        new LoanServicingHistory.Installment(1, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 9),
                                due, due),
                        new LoanServicingHistory.Installment(2, LocalDate.of(2026, 10, 10), null, due,
                                new BigDecimal("1000.00")),
                        new LoanServicingHistory.Installment(3, LocalDate.of(2026, 11, 10), null, due, BigDecimal.ZERO),
                        new LoanServicingHistory.Installment(4, LocalDate.of(2026, 12, 10), null, due,
                                BigDecimal.ZERO)))));

        AdminLoanInstallmentsResponse response = service.installments("LN-1");

        assertThat(response.businessDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(response.installments()).extracting(AdminLoanInstallmentsResponse.Installment::status)
                .containsExactly(InstallmentStatus.PAID, InstallmentStatus.OVERDUE, InstallmentStatus.DUE,
                        InstallmentStatus.UPCOMING);
        assertThat(response.paidCount()).isEqualTo(1);
        assertThat(response.overdueCount()).isEqualTo(1);
        assertThat(response.installments().get(0).paidOn()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(response.installments().get(1).totalPaid()).isEqualByComparingTo("1000.00");
    }

    @Test
    void installmentsWithoutNumberAreNumberedByDueDate() {
        AdminLoanInstallmentsResponse response = AdminLoanInstallmentServiceImpl.toResponse("LN-1",
                new LoanServicingHistory("LC-1", List.of(), List.of(
                        new LoanServicingHistory.Installment(LocalDate.of(2026, 12, 10), null),
                        new LoanServicingHistory.Installment(LocalDate.of(2026, 11, 10), null))),
                LocalDate.of(2026, 10, 1));

        assertThat(response.installments()).extracting(AdminLoanInstallmentsResponse.Installment::number)
                .containsExactly(1, 2);
        assertThat(response.installments().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 11, 10));
    }

    @Test
    void unreadableCoreIs503AndUnknownLoanIs404() {
        stubLoan();
        when(portfolioHistoryReader.read(anyList())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.installments("LN-1"))
                .isInstanceOf(LoanBusinessException.class)
                .hasMessageContaining("hệ thống lõi");
        assertThatThrownBy(() -> service.installments("LN-404")).isInstanceOf(ResourceNotFoundException.class);
    }

    private void stubLoan() {
        FinoraLoan loan = mock(FinoraLoan.class);
        when(loan.getId()).thenReturn(7L);
        when(loan.getFineractLoanId()).thenReturn(1020L);
        when(loan.getContractNumber()).thenReturn("LC-1");
        when(finoraLoanRepository.findByLoanNumber("LN-1")).thenReturn(Optional.of(loan));
    }
}
