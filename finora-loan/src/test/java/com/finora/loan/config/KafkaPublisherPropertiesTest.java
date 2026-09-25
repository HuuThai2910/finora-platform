package com.finora.loan.config;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaPublisherPropertiesTest {

    @Test
    void resolvesOnlyExactApprovedEventVersion() {
        KafkaPublisherProperties properties = new KafkaPublisherProperties(
                Duration.ofSeconds(5),
                List.of(new KafkaPublisherProperties.EventRoute(
                        "LoanTermsAuthorized", 1, "finora.loan.loan-terms-authorized"))
        );

        assertThat(properties.topicFor("LoanTermsAuthorized", 1))
                .isEqualTo("finora.loan.loan-terms-authorized");
        assertThat(properties.topicFor("LoanTermsAuthorized", 2)).isNull();
        assertThat(properties.topicFor("LoanContractSigned", 1)).isNull();
    }

    @Test
    void rejectsMissingDuplicateOrInvalidRoutes() {
        assertThatThrownBy(() -> new KafkaPublisherProperties(Duration.ofSeconds(5), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ít nhất một Kafka event route");

        KafkaPublisherProperties.EventRoute route = new KafkaPublisherProperties.EventRoute(
                "LoanTermsAuthorized", 1, "finora.loan.loan-terms-authorized");
        assertThatThrownBy(() -> new KafkaPublisherProperties(Duration.ofSeconds(5), List.of(route, route)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bị trùng");

        assertThatThrownBy(() -> new KafkaPublisherProperties.EventRoute(
                "LoanTermsAuthorized", 1, "loan-events"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finora.loan");
    }

    @Test
    void rejectsUnboundedAcknowledgementTimeout() {
        assertThatThrownBy(() -> new KafkaPublisherProperties(
                Duration.ofMinutes(2),
                List.of(new KafkaPublisherProperties.EventRoute(
                        "LoanTermsAuthorized", 1, "finora.loan.loan-terms-authorized"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 phút");
    }
}
