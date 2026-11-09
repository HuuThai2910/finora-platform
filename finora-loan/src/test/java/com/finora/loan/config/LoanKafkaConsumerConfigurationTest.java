package com.finora.loan.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

class LoanKafkaConsumerConfigurationTest {

    @Test
    void borrowerIdentityBackOffWaitsLongerThanTheDefaultButStopsWithinTheBudget() {
        BackOffExecution execution = LoanKafkaConsumerConfiguration.borrowerIdentityBackOff().start();

        assertThat(execution.nextBackOff()).isEqualTo(5_000L);
        assertThat(execution.nextBackOff()).isEqualTo(10_000L);
        long total = 15_000L;
        long next;
        int attempts = 2;
        while ((next = execution.nextBackOff()) != BackOffExecution.STOP) {
            // Mỗi khoảng chờ dưới max.poll.interval.ms mặc định (5 phút) để consumer không bị rebalance.
            assertThat(next).isLessThanOrEqualTo(60_000L);
            total += next;
            attempts++;
        }
        assertThat(attempts).isGreaterThan(5);
        assertThat(total).isLessThanOrEqualTo(660_000L);
    }
}
