package com.finora.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Bật {@link OperationalMonitoringScheduler} ở service có Micrometer (tức đã khai báo actuator). Là auto-configuration
 * (đăng ký trong {@code META-INF/spring/...AutoConfiguration.imports}) chứ không phải {@code @Configuration} được quét,
 * để {@code @ConditionalOnBean(MeterRegistry)} được đánh giá sau khi Spring Boot đã tạo registry.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
})
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@EnableConfigurationProperties(OperationalMetricsProperties.class)
public class OperationalMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    OperationalMonitoringScheduler operationalMonitoringScheduler(
            MeterRegistry registry,
            OperationalMetricsProperties properties,
            ObjectProvider<OperationalGaugeSet> gaugeSets,
            ObjectProvider<OperationalRefreshTask> tasks
    ) {
        return new OperationalMonitoringScheduler(registry, properties,
                gaugeSets.orderedStream().toList(), tasks.orderedStream().toList());
    }
}
