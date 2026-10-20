package com.finora.loan.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.messaging.event.LoanFundingExpiredEventData;
import com.finora.loan.messaging.event.RepaymentDistributedEventData;
import com.finora.loan.service.funding.LoanFundingExpiredHandler;
import com.finora.loan.service.servicing.RepaymentDistributedHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Consumer Loan của hai event mà producer khác bổ sung field ở W3F: {@code RepaymentDistributed.v1} có thêm
 * {@code borrowerId} (P4, Payment) và {@code LoanFundingExpired.v1} có thêm {@code investorIds} (P6, Investment). Record của
 * Loan không khai báo các field đó; ObjectMapper Spring Boot bỏ qua field lạ nên event vẫn được nhận như cũ.
 */
class ProducerFieldAdditionsCompatibilityTest {

    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void repaymentDistributedWithBorrowerId() {
        RepaymentDistributedHandler handler = mock(RepaymentDistributedHandler.class);

        new RepaymentDistributedConsumer(mapper, handler).consume(envelope("""
                {"repaymentId":"%s","repaymentReference":"FINORA-REPAY-1","repaymentType":"SCHEDULED","quoteId":null,
                 "platformFee":"0.00","investorFeeShare":"0.00","finoraFeeShare":"0.00","loanApplicationId":10,
                 "fineractLoanId":9001,"fineractTransactionId":901,"amount":"1726000.00","principalAmount":"1626000.00",
                 "interestAmount":"100000.00","feeAmount":"0.00","penaltyAmount":"0.00",
                 "outstandingPrincipal":"8374000.00","outstandingInterest":"0.00","outstandingFee":"0.00",
                 "outstandingPenalty":"0.00","totalOutstanding":"8374000.00","overdueAmount":"0.00","currency":"VND",
                 "transactionDate":"2026-11-17","nextDueDate":"2026-12-17","nextDueAmount":"1726000.00",
                 "completedAt":"2026-11-17T01:00:00Z","allocations":[],"borrowerId":"42"}
                """.formatted(UUID.randomUUID())), EVENT_ID.toString(), "RepaymentDistributed", "1");

        ArgumentCaptor<RepaymentDistributedEventData> data = ArgumentCaptor.forClass(RepaymentDistributedEventData.class);
        verify(handler).handle(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().totalOutstanding()).isEqualTo("8374000.00");
    }

    @Test
    void fundingExpiredWithInvestorIds() {
        LoanFundingExpiredHandler handler = mock(LoanFundingExpiredHandler.class);

        new LoanFundingExpiredConsumer(mapper, handler).consume(envelope("""
                {"loanApplicationId":10,"applicationNumber":"LA-10","listingId":20,"fundingRound":1,
                 "targetAmount":"50000000.00","committedAmount":"6000000.00","currency":"VND",
                 "fundingOpenedAt":"2026-10-01T00:00:00Z","fundingClosedAt":"2026-10-15T00:00:00Z",
                 "reason":"FUNDING_WINDOW_EXPIRED","investorIds":["INV-1","INV-2"]}
                """), EVENT_ID.toString(), "LoanFundingExpired", "1");

        ArgumentCaptor<LoanFundingExpiredEventData> data = ArgumentCaptor.forClass(LoanFundingExpiredEventData.class);
        verify(handler).handle(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().reason()).isEqualTo("FUNDING_WINDOW_EXPIRED");
    }

    private static String envelope(String data) {
        return """
                {"eventId":"%s","occurredAt":"2026-10-17T03:00:01Z","version":1,"data":%s}
                """.formatted(EVENT_ID, data.strip());
    }
}
