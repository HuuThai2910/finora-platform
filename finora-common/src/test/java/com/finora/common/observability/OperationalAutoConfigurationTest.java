package com.finora.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

class OperationalAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    OperationalMetricsAutoConfiguration.class, KafkaHealthAutoConfiguration.class));

    @Test
    void schedulerIsCreatedWhenAMeterRegistryExistsAndPicksUpGaugeSets() {
        runner.withUserConfiguration(MeterRegistryConfiguration.class, GaugeSetConfiguration.class)
                .withPropertyValues("finora.operations.metrics.initial-delay=1h")
                .run(context -> {
                    assertThat(context).hasSingleBean(OperationalMonitoringScheduler.class);
                    context.getBean(OperationalMonitoringScheduler.class).refreshNow();
                    assertThat(context.getBean(MeterRegistry.class).get(OperationalGauge.OUTBOX_EVENTS)
                            .tag("service", "loan").gauge().value()).isEqualTo(5.0);
                });
    }

    @Test
    void noSchedulerWithoutMeterRegistry() {
        runner.run(context -> assertThat(context).doesNotHaveBean(OperationalMonitoringScheduler.class));
    }

    @Test
    void propertiesBindFromConfiguration() {
        runner.withUserConfiguration(MeterRegistryConfiguration.class)
                .withPropertyValues("finora.operations.metrics.refresh-interval=2m",
                        "finora.operations.metrics.count-cap=500")
                .run(context -> {
                    OperationalMetricsProperties properties = context.getBean(OperationalMetricsProperties.class);
                    assertThat(properties.refreshInterval()).hasMinutes(2);
                    assertThat(properties.countCap()).isEqualTo(500);
                    assertThat(properties.enabled()).isTrue();
                });
    }

    @Test
    void kafkaHealthIndicatorIsRegisteredWhenKafkaAdminExists() {
        runner.withUserConfiguration(KafkaAdminConfiguration.class)
                .withPropertyValues("finora.operations.health.kafka.timeout=5s")
                .run(context -> {
                    assertThat(context).hasBean("kafkaHealthIndicator")
                            .hasSingleBean(KafkaClusterHealthIndicator.class);
                    KafkaHealthProperties properties = context.getBean(KafkaHealthProperties.class);
                    assertThat(properties.timeout()).hasSeconds(5);
                    assertThat(properties.cacheTtl()).hasSeconds(30);
                });
    }

    @Test
    void kafkaHealthIndicatorIsOrderedAfterBootKafkaAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                        KafkaHealthAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(KafkaAdmin.class)
                        .hasSingleBean(KafkaClusterHealthIndicator.class));
    }

    @Test
    void autoConfigurationsHaveNoNestedConfigurationClasses() {
        // Service quét com.finora.common: class @Configuration lồng sẽ bị component scan nhận như cấu hình thường,
        // chạy trước auto-configuration của Spring Boot và @ConditionalOnBean luôn sai (lỗi đã gặp ở Loan).
        assertThat(KafkaHealthAutoConfiguration.class.getDeclaredClasses()).isEmpty();
        assertThat(OperationalMetricsAutoConfiguration.class.getDeclaredClasses()).isEmpty();
    }

    @Test
    void kafkaHealthIndicatorCanBeDisabledPerService() {
        runner.withUserConfiguration(KafkaAdminConfiguration.class)
                .withPropertyValues("management.health.kafka.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(KafkaClusterHealthIndicator.class));
    }

    @Test
    void noKafkaHealthIndicatorWithoutKafkaAdmin() {
        runner.run(context -> assertThat(context).doesNotHaveBean(KafkaClusterHealthIndicator.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GaugeSetConfiguration {
        @Bean
        OperationalGaugeSet loanGauges() {
            return new OperationalGaugeSet("loan", List.of(OperationalGauge.outboxEvents("pending", () -> 5)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class KafkaAdminConfiguration {
        @Bean
        KafkaAdmin kafkaAdmin() {
            // Không kết nối khi tạo bean; test chỉ kiểm điều kiện đăng ký indicator.
            return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1"));
        }
    }
}
