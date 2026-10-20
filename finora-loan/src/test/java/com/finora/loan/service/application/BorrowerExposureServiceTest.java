package com.finora.loan.service.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.loan.config.BorrowerExposureProperties;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.servicing.FinoraLoanRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class BorrowerExposureServiceTest {

    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final FinoraLoanRepository loans = mock(FinoraLoanRepository.class);

    private BorrowerExposureService service(Boolean enabled) {
        return new BorrowerExposureService(
                new BorrowerExposureProperties(enabled, new BigDecimal("100000000")), applications, loans);
    }

    @Test
    void acceptsRequestThatReachesLimitExactly() {
        when(loans.sumOutstandingPrincipal(eq("B-1"), anyList())).thenReturn(new BigDecimal("40000000.00"));
        when(applications.sumOpenRequestedAmount(eq("B-1"), any(), any(), any())).thenReturn(new BigDecimal("10000000.00"));

        assertThatCode(() -> service(true).requireWithinLimit("B-1", new BigDecimal("50000000.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void countsOutstandingAndPendingApplicationsNotOnlyTheNewRequest() {
        when(loans.sumOutstandingPrincipal(eq("B-1"), anyList())).thenReturn(new BigDecimal("40000000.00"));
        when(applications.sumOpenRequestedAmount(eq("B-1"), any(), any(), any())).thenReturn(new BigDecimal("30000000.00"));

        // Hồ sơ mới chỉ 40 triệu nhưng cộng dư nợ và hồ sơ đang xử lý là 110 triệu: vượt hạn mức 100 triệu.
        assertThatThrownBy(() -> service(true).requireWithinLimit("B-1", new BigDecimal("40000000.00")))
                .isInstanceOf(LoanBusinessException.class)
                .hasMessage("Tổng dư nợ và hồ sơ đang xử lý vượt hạn mức 100.000.000 đồng; "
                        + "số tiền còn có thể đề nghị: 30.000.000 đồng")
                .extracting("code").isEqualTo("BORROWER_EXPOSURE_LIMIT_EXCEEDED");
    }

    @Test
    void locksBorrowerBeforeReadingTotals() {
        when(loans.sumOutstandingPrincipal(eq("B-2"), anyList())).thenReturn(BigDecimal.ZERO);
        when(applications.sumOpenRequestedAmount(eq("B-2"), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        service(true).requireWithinLimit("B-2", new BigDecimal("1000000.00"));

        InOrder order = inOrder(applications, loans);
        order.verify(applications).lockBorrowerExposure("finora-loan-exposure:B-2");
        order.verify(loans).sumOutstandingPrincipal(eq("B-2"), anyList());
    }

    @Test
    void disabledPolicySkipsCheck() {
        service(false).requireWithinLimit("B-3", new BigDecimal("900000000.00"));

        verify(applications, never()).lockBorrowerExposure(any());
    }
}
