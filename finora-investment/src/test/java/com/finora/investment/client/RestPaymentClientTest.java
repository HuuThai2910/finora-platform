package com.finora.investment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.client.impl.RestPaymentClient;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** R3: lời gọi giữ/nhả/tra cứu khoản giữ phân loại kết quả theo đúng trạng thái và mã lỗi Payment trả. */
class RestPaymentClientTest {

    private MockRestServiceServer server;
    private RestPaymentClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
        when(tokens.accessToken()).thenReturn("service-token");
        client = new RestPaymentClient(builder, new ObjectMapper(), tokens);
        ReflectionTestUtils.setField(client, "baseUrl", "http://payment.test/api/v1");
    }

    @Test
    void replayedHoldThatWasAlreadyReleasedIsNeverTreatedAsMoneyHeld() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"holdReference":"HOLD-1","amount":2000000.00,"currency":"VND","status":"RELEASED"}
                        """, MediaType.APPLICATION_JSON));

        PaymentHoldResult result = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isAlreadyReleased()).isTrue();
        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getHoldReference()).isEqualTo("HOLD-1");
        server.verify();
    }

    @Test
    void authorizationFailureOnHoldStaysPendingInsteadOfRejectingTheOrder() {
        // Token hoặc quyền lỗi không chứng minh lần gọi trước chưa giữ tiền: để lệnh chờ và đối soát lại.
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ACCESS_DENIED\",\"message\":\"forbidden\"}"));

        PaymentHoldResult result = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-2");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isTrue();
        server.verify();
    }

    @Test
    void insufficientBalanceIsADefinitiveRejection() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_INSUFFICIENT_BALANCE\",\"message\":\"Số dư không đủ\"}"));

        PaymentHoldResult result = client.hold("INV-1", new BigDecimal("2000000.00"), "IO-3");

        assertThat(result.isRetryable()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("PAYMENT_INSUFFICIENT_BALANCE");
        server.verify();
    }

    @Test
    void holdLookupReturnsEmptyOnlyWhenPaymentNeverHeldMoneyForTheOrder() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds?orderReference=IO-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_HOLD_NOT_FOUND\",\"message\":\"Không tìm thấy\"}"));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds?orderReference=IO-5"))
                .andRespond(withSuccess("""
                        {"holdReference":"HOLD-5","amount":1000000.00,"currency":"VND","status":"HELD"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds?orderReference=IO-6"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(client.findHold("IO-4")).isEmpty();
        assertThat(client.findHold("IO-5")).hasValueSatisfying(hold -> {
            assertThat(hold.held()).isTrue();
            assertThat(hold.amount()).isEqualByComparingTo("1000000.00");
        });
        assertThatThrownBy(() -> client.findHold("IO-6"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> assertThat(exception.isRetryable()).isTrue());
        server.verify();
    }

    @Test
    void releaseOfAHoldAlreadyInDisbursementStopsRetryingButANetworkErrorDoesNot() {
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds/HOLD-7/release?orderReference=IO-7"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PAYMENT_HOLD_NOT_RELEASABLE\",\"message\":\"đã capture\"}"));
        server.expect(requestTo("http://payment.test/api/v1/transactions/holds/HOLD-8/release?orderReference=IO-8"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.release("HOLD-7", "IO-7"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("PAYMENT_HOLD_NOT_RELEASABLE");
                    assertThat(exception.isRetryable()).isFalse();
                });
        assertThatThrownBy(() -> client.release("HOLD-8", "IO-8"))
                .isInstanceOfSatisfying(PaymentCallException.class, exception -> assertThat(exception.isRetryable()).isTrue());
        server.verify();
    }
}
