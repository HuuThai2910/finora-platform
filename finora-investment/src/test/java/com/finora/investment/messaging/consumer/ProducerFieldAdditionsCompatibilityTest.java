package com.finora.investment.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.finora.investment.messaging.event.LoanContractActivatedEventData;
import com.finora.investment.messaging.event.LoanDisbursedEventData;
import com.finora.investment.messaging.event.LoanFundingCancelledEventData;
import com.finora.investment.messaging.event.LoanRescheduleConsentRequestedEventData;
import com.finora.investment.messaging.event.LoanRescheduledEventData;
import com.finora.investment.messaging.event.RepaymentDistributedEventData;
import com.finora.investment.service.messaging.ContractLifecycleEventHandler;
import com.finora.investment.service.messaging.LoanDisbursedEventHandler;
import com.finora.investment.service.messaging.LoanFundingCancelledHandler;
import com.finora.investment.service.messaging.LoanRescheduleConsentRequestedHandler;
import com.finora.investment.service.messaging.LoanServicingLifecycleEventHandler;
import com.finora.investment.service.messaging.RepaymentDistributedEventHandler;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Consumer Investment của sáu event được producer bổ sung field ở W3F (P2–P4) vẫn đọc đúng payload mới: record của
 * Investment không khai báo {@code borrowerId}/{@code nextDueDate}/{@code nextDueAmount} và ObjectMapper của Spring Boot
 * bỏ qua field lạ ({@code FAIL_ON_UNKNOWN_PROPERTIES} tắt; không service nào tự dựng ObjectMapper khác). Version giữ v1.
 */
class ProducerFieldAdditionsCompatibilityTest {

    private static final UUID EVENT_ID = UUID.fromString("8cbcb833-a674-4ed4-94ae-71619d65e0d8");
    // Cùng mặc định Spring Boot dùng cho bean ObjectMapper của Investment.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

    @Test
    void contractActivatedWithBorrowerId() {
        ContractLifecycleEventHandler handler = mock(ContractLifecycleEventHandler.class);

        new LoanContractActivatedConsumer(mapper, handler).consume(envelope("""
                {"loanApplicationId":10,"applicationNumber":"LA-10","contractNumber":"LC-10","listingId":20,
                 "documentHash":"%s","pdfReceiptHash":"%s","allocationVersion":1,"allocationHash":"%s",
                 "activatedAt":"2026-10-17T03:00:00Z","borrowerId":"42"}
                """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64))), EVENT_ID.toString(),
                "LoanContractActivated", "1");

        ArgumentCaptor<LoanContractActivatedEventData> data = ArgumentCaptor.forClass(LoanContractActivatedEventData.class);
        verify(handler).handleActivated(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().contractNumber()).isEqualTo("LC-10");
    }

    @Test
    void loanDisbursedWithBorrowerIdAndNextDue() throws Exception {
        LoanDisbursedEventHandler handler = mock(LoanDisbursedEventHandler.class);
        String data = """
                {"sagaId":"%s","loanApplicationId":10,"applicationNumber":"LA-10","contractNumber":"LC-10",
                 "listingId":20,"amount":"10000000.00","currency":"VND","paymentReference":"MOCK-10",
                 "fineractLoanId":9001,"coreConfigVersion":"FINORA-FINERACT-V4","disbursedAt":"2026-10-17T03:00:00Z",
                 "earlySettlementPolicyVersion":"AGRIBANK_REFERENCE_FINORA_2026_V2","loanNumber":"LN-10",
                 "maturityDate":"2027-04-17","borrowerId":"42","nextDueDate":"2026-11-17","nextDueAmount":"1726000.00"}
                """.formatted(UUID.randomUUID());

        new LoanDisbursedConsumer(mapper, handler).consume(envelope(data), EVENT_ID.toString(), "LoanDisbursed", "1");

        ArgumentCaptor<LoanDisbursedEventData> parsed = ArgumentCaptor.forClass(LoanDisbursedEventData.class);
        verify(handler).handle(eq(EVENT_ID), any(), parsed.capture());
        assertThat(parsed.getValue().maturityDate()).isEqualTo(LocalDate.of(2027, 4, 17));
        // Đối chứng: chỉ nhờ cấu hình bỏ qua field lạ; một ObjectMapper "nghiêm" sẽ từ chối đúng các field mới.
        ObjectMapper strict = new ObjectMapper().findAndRegisterModules();
        assertThatThrownBy(() -> strict.readValue(data, LoanDisbursedEventData.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("earlySettlementPolicyVersion");
    }

    @Test
    void fundingCancelledWithBorrowerId() {
        LoanFundingCancelledHandler handler = mock(LoanFundingCancelledHandler.class);

        new LoanFundingCancelledConsumer(mapper, handler).consume(envelope("""
                {"loanApplicationId":10,"applicationNumber":"LA-10","listingId":20,"fundingRound":1,
                 "contractNumber":"LC-10","allocationVersion":1,"allocationHash":"%s","reasonCode":"CONTRACT_EXPIRED",
                 "cancelledAt":"2026-10-17T03:00:00Z","borrowerId":"42"}
                """.formatted("c".repeat(64))), EVENT_ID.toString(), "LoanFundingCancelled", "1");

        ArgumentCaptor<LoanFundingCancelledEventData> data = ArgumentCaptor.forClass(LoanFundingCancelledEventData.class);
        verify(handler).handle(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().reasonCode()).isEqualTo("CONTRACT_EXPIRED");
    }

    @Test
    void rescheduleConsentRequestedWithBorrowerId() {
        LoanRescheduleConsentRequestedHandler handler = mock(LoanRescheduleConsentRequestedHandler.class);

        new LoanRescheduleConsentRequestedConsumer(mapper, handler).consume(envelope("""
                {"requestId":"11111111-2222-3333-4444-555555555555","loanApplicationId":10,"loanNumber":"LN-10",
                 "requestType":"TERM_EXTENSION","rescheduleFromDate":"2026-11-01","adjustedDueDate":null,
                 "extraTerms":2,"originalMaturityDate":"2027-08-01","requestedAt":"2026-10-17T03:00:00Z",
                 "borrowerId":"42"}
                """), EVENT_ID.toString(), "LoanRescheduleConsentRequested", "1");

        ArgumentCaptor<LoanRescheduleConsentRequestedEventData> data =
                ArgumentCaptor.forClass(LoanRescheduleConsentRequestedEventData.class);
        verify(handler).handle(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().extraTerms()).isEqualTo(2);
    }

    @Test
    void rescheduledWithNextDue() {
        LoanServicingLifecycleEventHandler handler = mock(LoanServicingLifecycleEventHandler.class);

        new LoanServicingLifecycleConsumer(mapper, handler).rescheduled(envelope("""
                {"loanApplicationId":10,"loanNumber":"LN-10","borrowerId":"42","fineractLoanId":9001,
                 "requestId":"11111111-2222-3333-4444-555555555555","requestType":"TERM_EXTENSION",
                 "previousMaturityDate":"2027-08-01","newMaturityDate":"2027-10-01","rescheduleFromDate":"2026-11-01",
                 "adjustedDueDate":null,"extraTerms":2,"occurredAt":"2026-10-17T03:00:00Z",
                 "nextDueDate":"2026-12-01","nextDueAmount":"1234567.00"}
                """), EVENT_ID.toString(), "LoanRescheduled", "1");

        ArgumentCaptor<LoanRescheduledEventData> data = ArgumentCaptor.forClass(LoanRescheduledEventData.class);
        verify(handler).rescheduled(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().newMaturityDate()).isEqualTo(LocalDate.of(2027, 10, 1));
    }

    @Test
    void repaymentDistributedWithBorrowerId() {
        RepaymentDistributedEventHandler handler = mock(RepaymentDistributedEventHandler.class);

        new RepaymentDistributedConsumer(mapper, handler).consume(envelope("""
                {"repaymentId":"%s","repaymentReference":"FINORA-REPAY-1","repaymentType":"SCHEDULED","quoteId":null,
                 "platformFee":"0.00","investorFeeShare":"0.00","finoraFeeShare":"0.00","loanApplicationId":10,
                 "fineractLoanId":9001,"fineractTransactionId":901,"amount":"1726000.00","principalAmount":"1626000.00",
                 "interestAmount":"100000.00","feeAmount":"0.00","penaltyAmount":"0.00",
                 "outstandingPrincipal":"8374000.00","outstandingInterest":"0.00","outstandingFee":"0.00",
                 "outstandingPenalty":"0.00","totalOutstanding":"8374000.00","overdueAmount":"0.00","currency":"VND",
                 "transactionDate":"2026-11-17","nextDueDate":"2026-12-17","nextDueAmount":"1726000.00",
                 "completedAt":"2026-11-17T01:00:00Z","allocations":[{"noteId":1,"noteNumber":"NT-1",
                 "investorId":"INV-1","principalAmount":"1626000.00","interestAmount":"100000.00",
                 "prepaymentFeeAmount":"0.00"}],"borrowerId":"42"}
                """.formatted(UUID.randomUUID())), EVENT_ID.toString(), "RepaymentDistributed", "1");

        ArgumentCaptor<RepaymentDistributedEventData> data = ArgumentCaptor.forClass(RepaymentDistributedEventData.class);
        verify(handler).handle(eq(EVENT_ID), any(), data.capture());
        assertThat(data.getValue().allocations()).singleElement()
                .satisfies(allocation -> assertThat(allocation.investorId()).isEqualTo("INV-1"));
    }

    private static String envelope(String data) {
        return """
                {"eventId":"%s","occurredAt":"2026-10-17T03:00:01Z","version":1,"data":%s}
                """.formatted(EVENT_ID, data.strip());
    }
}
