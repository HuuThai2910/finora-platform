package com.finora.payment.integration.fineract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.payment.config.PaymentFineractConfiguration;
import com.finora.payment.config.PaymentFineractProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Socket thật thay cho Fineract: request repayment đã tới core rồi mới mất phản hồi. Drill Q13 qua Toxiproxy cho thấy
 * RestClient với {@code SimpleClientHttpRequestFactory} ném lỗi này dưới dạng {@code RestClientException}, không phải
 * {@code ResourceAccessException} (B42), nên kiểm cả cách client thật bọc lỗi chứ không dựng exception bằng tay.
 */
class FineractRepaymentConnectionLossTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    @Test
    void responseLostAfterRepaymentWasSentIsOutcomeUnknown() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = serveOnce(server, socket -> Thread.sleep(READ_TIMEOUT.toMillis() * 4));

            assertOutcomeUnknown(gateway(server.getLocalPort()));
            assertThat(received.get(5, TimeUnit.SECONDS)).startsWith("POST /loans/77/transactions?command=repayment");
        }
    }

    @Test
    void connectionResetAfterRepaymentWasSentIsOutcomeUnknown() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = serveOnce(server, socket -> {
                // SO_LINGER 0: đóng bằng RST, giống toxic reset_peer của drill FineractRequestLost.
                socket.setSoLinger(true, 0);
            });

            assertOutcomeUnknown(gateway(server.getLocalPort()));
            assertThat(received.get(5, TimeUnit.SECONDS)).startsWith("POST /loans/77/transactions?command=repayment");
        }
    }

    private static void assertOutcomeUnknown(FineractRepaymentGateway gateway) {
        RepaymentCoreCommand command = new RepaymentCoreCommand(77L,
                "FINORA-REPAY-" + UUID.randomUUID(), LocalDate.of(2026, 11, 17), new BigDecimal("2680000.00"));
        assertThatThrownBy(() -> gateway.postRepayment(command))
                .isInstanceOfSatisfying(FineractRepaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("FINERACT_REPAYMENT_OUTCOME_UNKNOWN");
                    assertThat(exception.outcomeUnknown()).isTrue();
                });
    }

    private static FineractRepaymentGateway gateway(int port) {
        PaymentFineractProperties properties = new PaymentFineractProperties(
                "http://127.0.0.1:" + port, "default", "mifos", "password", 1L, Duration.ofSeconds(1), READ_TIMEOUT);
        RestClient client = new PaymentFineractConfiguration().paymentFineractRestClient(properties, RestClient.builder());
        return new FineractRepaymentGateway(client, properties);
    }

    /** Nhận đúng một kết nối, đọc hết request (header + body) rồi mới cắt, để lỗi rơi vào lúc chờ phản hồi. */
    private static CompletableFuture<String> serveOnce(ServerSocket server, SocketAction beforeClose) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String requestLine = reader.readLine();
                int contentLength = 0;
                for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                    if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                char[] body = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int count = reader.read(body, read, contentLength - read);
                    if (count < 0) break;
                    read += count;
                }
                beforeClose.run(socket);
                return requestLine;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @FunctionalInterface
    private interface SocketAction {
        void run(Socket socket) throws Exception;
    }
}
