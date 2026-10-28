package com.finora.loan.integration.fineract.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.loan.config.FineractBookingProperties;
import com.finora.loan.domain.product.RepaymentMethod;
import com.finora.loan.integration.fineract.mapper.FineractRepaymentPolicy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FineractCoreLoanBookingGatewayTest {

    /** Trạng thái, ngày đáo hạn và kỳ đầu đọc trong cùng một lần đọc khoản vay kèm lịch (B34, P3). */
    private static final String LOAN_WITH_SCHEDULE = "http://fineract.test/loans/3?associations=repaymentSchedule";

    @Test
    void createsV2ClientAndLoanUsingTenantRequiredFieldsThenDisburses() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractCoreLoanBookingGateway gateway = new FineractCoreLoanBookingGateway(
                builder.build(), passThroughExecutor(),
                new FineractBookingProperties(1L, 1L, 1L, "en", "yyyy-MM-dd"),
                new FineractRepaymentPolicy(),
                Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC));

        server.expect(requestTo("http://fineract.test/clients?externalId=FINORA-BORROWER-BORROWER-1"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/clients"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"officeId\":1"),
                        Matchers.containsString("\"legalFormId\":1"),
                        Matchers.containsString("\"externalId\":\"FINORA-BORROWER-BORROWER-1\""))))
                .andRespond(withSuccess("{\"resourceId\":2}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans?externalId=LC-1"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"interestCalculationPeriodType\":1"),
                        Matchers.containsString("\"amortizationType\":1"),
                        Matchers.containsString("\"transactionProcessingStrategyCode\":\"advanced-payment-allocation-strategy\""))))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("{\"status\":{\"pendingApproval\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=approve"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        // Khoản đã duyệt có lịch theo ngày giải ngân thực tế: ngày đáo hạn (B34) và kỳ đầu (P3) cùng lần đọc này đi theo
        // LoanDisbursed. Dòng giải ngân (không có số kỳ) không phải kỳ phải trả.
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("""
                        {"status":{"approved":true},"timeline":{"expectedMaturityDate":[2027,4,4]},
                         "repaymentSchedule":{"periods":[
                           {"dueDate":[2026,10,4],"principalDisbursed":50000000.000000},
                           {"period":1,"dueDate":[2026,11,4],"totalOutstandingForPeriod":8701681.000000},
                           {"period":2,"dueDate":[2026,12,4],"totalOutstandingForPeriod":8701681.000000}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=disburse"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("\"paymentTypeId\":1")))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));

        var result = gateway.bookAndDisburse(new CoreLoanBookingGateway.CoreLoanBookingCommand(
                1L, "LC-1", "BORROWER-1", 2L, new BigDecimal("50000000"), 6,
                new BigDecimal("15"), RepaymentMethod.ANNUITY, "FINORA-FINERACT-V2",
                LocalDate.of(2026, 10, 4), "PAY-1"));

        assertThat(result.fineractLoanId()).isEqualTo(3L);
        assertThat(result.maturityDate()).isEqualTo(LocalDate.of(2027, 4, 4));
        assertThat(result.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(result.nextDueAmount()).isEqualByComparingTo("8701681.00");
        assertThat(result.nextDueAmount().scale()).isEqualTo(2);
        server.verify();
    }

    @Test
    void reconciledActiveLoanUsesItsOwnScheduleWithoutApprovingOrDisbursingAgain() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractCoreLoanBookingGateway gateway = new FineractCoreLoanBookingGateway(
                builder.build(), passThroughExecutor(),
                new FineractBookingProperties(1L, 1L, 1L, "en", "yyyy-MM-dd"),
                new FineractRepaymentPolicy(),
                Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC));

        server.expect(requestTo("http://fineract.test/clients?externalId=FINORA-BORROWER-BORROWER-1"))
                .andRespond(withSuccess("{\"pageItems\":[{\"id\":2}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans?externalId=LC-1"))
                .andRespond(withSuccess("{\"pageItems\":[{\"id\":3}]}", MediaType.APPLICATION_JSON));
        // Lần thử trước đã giải ngân trên core (worker chết trước khi lưu saga): đọc đúng một lần, không gửi lệnh nào.
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("""
                        {"status":{"active":true},"timeline":{"expectedMaturityDate":[2027,4,4]},
                         "repaymentSchedule":{"periods":[
                           {"dueDate":[2026,10,4],"principalDisbursed":50000000.000000},
                           {"period":1,"dueDate":[2026,11,4],"totalOutstandingForPeriod":8701681.50}]}}
                        """, MediaType.APPLICATION_JSON));

        var result = gateway.bookAndDisburse(new CoreLoanBookingGateway.CoreLoanBookingCommand(
                1L, "LC-1", "BORROWER-1", 2L, new BigDecimal("50000000"), 6,
                new BigDecimal("15"), RepaymentMethod.ANNUITY, "FINORA-FINERACT-V2",
                LocalDate.of(2026, 10, 4), "PAY-1"));

        assertThat(result.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(result.nextDueAmount()).isEqualByComparingTo("8701681.50");
        server.verify();
    }

    @Test
    void nextDueIsTheEarliestUnpaidRepaymentPeriodOrAbsent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Dòng giải ngân còn phí (không có số kỳ) và kỳ 1 đã trả không được chọn; thứ tự trong mảng không quan trọng.
        var periods = mapper.readTree("""
                [{"dueDate":[2026,10,4],"totalOutstandingForPeriod":150000.00},
                 {"period":3,"dueDate":[2027,1,4],"totalOutstandingForPeriod":8701681.00},
                 {"period":1,"dueDate":[2026,11,4],"totalOutstandingForPeriod":0},
                 {"period":2,"dueDate":[2026,12,4],"totalOutstandingForPeriod":8701681.00}]
                """);

        var next = FineractCoreLoanBookingGateway.nextDue(periods);

        assertThat(next.date()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(next.amount()).isEqualByComparingTo("8701681.00");
        // Core không trả lịch (hoặc mọi kỳ đã trả): bỏ trống, không tự tính (D76).
        assertThat(FineractCoreLoanBookingGateway.nextDue(mapper.readTree("{}").path("periods"))).isNull();
        assertThat(FineractCoreLoanBookingGateway.nextDue(mapper.readTree(
                "[{\"period\":1,\"dueDate\":[2026,11,4],\"totalOutstandingForPeriod\":0}]"))).isNull();
    }

    @Test
    void amountThatIsNotExactlyTwoDecimalsIsDroppedInsteadOfRounded() {
        var result = new CoreLoanBookingGateway.CoreLoanBookingResult(3L, LocalDate.of(2027, 4, 4),
                LocalDate.of(2026, 11, 4), new BigDecimal("8701681.125"));

        // Field chỉ phục vụ thông báo: không làm tròn tiền và không làm hỏng lần ghi core.
        assertThat(result.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(result.nextDueAmount()).isNull();
        assertThat(new CoreLoanBookingGateway.CoreLoanBookingResult(3L, null, null, new BigDecimal("8701681"))
                .nextDueAmount()).hasToString("8701681.00");
    }

    @Test
    void v4LoanIsBookedWithDailyInterestCalculationLikeItsProduct() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FineractCoreLoanBookingGateway gateway = new FineractCoreLoanBookingGateway(
                builder.build(), passThroughExecutor(),
                new FineractBookingProperties(1L, 1L, 1L, "en", "yyyy-MM-dd"),
                new FineractRepaymentPolicy(),
                Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC));

        server.expect(requestTo("http://fineract.test/clients?externalId=FINORA-BORROWER-BORROWER-1"))
                .andRespond(withSuccess("{\"pageItems\":[{\"id\":2}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans?externalId=LC-4"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        // Product V4 chỉ nhận khoản vay tính lãi theo ngày (Fineract 1.15 bắt buộc khi bật tính lại lãi).
        server.expect(requestTo("http://fineract.test/loans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"interestCalculationPeriodType\":0"),
                        Matchers.containsString("\"amortizationType\":0"),
                        Matchers.containsString("\"transactionProcessingStrategyCode\":\"mifos-standard-strategy\""))))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("{\"status\":{\"pendingApproval\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=approve"))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("{\"status\":{\"approved\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=disburse"))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));

        gateway.bookAndDisburse(new CoreLoanBookingGateway.CoreLoanBookingCommand(
                1L, "LC-4", "BORROWER-1", 2L, new BigDecimal("10000000"), 6,
                new BigDecimal("12"), RepaymentMethod.EQUAL_PRINCIPAL, "FINORA-FINERACT-V4",
                LocalDate.of(2026, 10, 17), "PAY-4"));

        server.verify();
    }

    @Test
    void booksEveryCoreDateOnTheVietnamBusinessDateOfActualDisbursement() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fineract.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // 03:00 ngày 17/10 giờ Việt Nam là 20:00Z ngày 16/10: core phải ghi ngày 17/10, không lùi theo UTC.
        FineractCoreLoanBookingGateway gateway = new FineractCoreLoanBookingGateway(
                builder.build(), passThroughExecutor(),
                new FineractBookingProperties(1L, 1L, 1L, "en", "yyyy-MM-dd"),
                new FineractRepaymentPolicy(),
                Clock.fixed(Instant.parse("2026-10-16T20:00:00Z"), java.time.ZoneId.of("Asia/Ho_Chi_Minh")));

        server.expect(requestTo("http://fineract.test/clients?externalId=FINORA-BORROWER-BORROWER-1"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/clients"))
                .andExpect(content().string(Matchers.containsString("\"activationDate\":\"2026-10-17\"")))
                .andRespond(withSuccess("{\"resourceId\":2}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans?externalId=LC-1"))
                .andRespond(withSuccess("{\"pageItems\":[]}", MediaType.APPLICATION_JSON));
        // Hợp đồng ghi ngày dự kiến 01/10 (lúc nộp hồ sơ); gọi vốn và ký kéo dài nên booking phải dùng ngày thực tế,
        // nếu không Fineract từ chối vì ngày nộp sau ngày giải ngân dự kiến.
        server.expect(requestTo("http://fineract.test/loans"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"expectedDisbursementDate\":\"2026-10-17\""),
                        Matchers.containsString("\"submittedOnDate\":\"2026-10-17\""))))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("{\"status\":{\"pendingApproval\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=approve"))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("\"approvedOnDate\":\"2026-10-17\""),
                        Matchers.containsString("\"expectedDisbursementDate\":\"2026-10-17\""))))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOAN_WITH_SCHEDULE))
                .andRespond(withSuccess("{\"status\":{\"approved\":true}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://fineract.test/loans/3?command=disburse"))
                .andExpect(content().string(Matchers.containsString("\"actualDisbursementDate\":\"2026-10-17\"")))
                .andRespond(withSuccess("{\"resourceId\":3}", MediaType.APPLICATION_JSON));

        gateway.bookAndDisburse(new CoreLoanBookingGateway.CoreLoanBookingCommand(
                1L, "LC-1", "BORROWER-1", 2L, new BigDecimal("50000000"), 6,
                new BigDecimal("15"), RepaymentMethod.ANNUITY, "FINORA-FINERACT-V2",
                LocalDate.of(2026, 10, 1), "PAY-1"));

        server.verify();
    }

    private FineractRequestExecutor passThroughExecutor() {
        return new FineractRequestExecutor(null, null, new ObjectMapper()) {
            @Override
            public void authenticate(HttpHeaders headers) {
                // Unit test chỉ kiểm tra payload; xác thực được kiểm tra riêng ở FineractRequestExecutorTest.
            }

            @Override
            public <T> T execute(FineractCallGroup group, String operation, ExternalCall<T> call) {
                return call.execute();
            }
        };
    }
}
