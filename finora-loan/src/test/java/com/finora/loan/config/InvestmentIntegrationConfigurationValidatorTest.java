package com.finora.loan.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvestmentIntegrationConfigurationValidatorTest {

    @Test
    void rejectsIncompleteKafkaPathBecauseInvestmentFlowIsMandatory() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.loan.outbox.publisher-enabled", "true")
                .withProperty("finora.loan.outbox.transport", "none")
                .withProperty("finora.loan.kafka.consumer-enabled", "true");

        assertThatThrownBy(validator(environment)::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finora.loan.outbox.transport=kafka");
    }

    @Test
    void acceptsCompleteInvestmentKafkaConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("finora.loan.outbox.publisher-enabled", "true")
                .withProperty("finora.loan.outbox.transport", "kafka")
                .withProperty("finora.loan.kafka.consumer-enabled", "true");

        assertThatCode(validator(environment)::afterPropertiesSet).doesNotThrowAnyException();
    }

    private InvestmentIntegrationConfigurationValidator validator(MockEnvironment environment) {
        return new InvestmentIntegrationConfigurationValidator(environment);
    }
}
