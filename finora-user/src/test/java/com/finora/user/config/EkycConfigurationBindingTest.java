package com.finora.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nạp đúng {@code application.yml} (không dựng context, không đọc {@code .env}) rồi bind các nhóm cấu hình eKYC,
 * để sai tên khoá hoặc mặc định lệch hợp đồng bị phát hiện ngay ở unit test.
 */
class EkycConfigurationBindingTest {

    private static Binder binder() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        // Bỏ biến môi trường của máy chạy test để chỉ kiểm giá trị mặc định trong file.
        propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.forEach(propertySources::addLast);
        return new Binder(ConfigurationPropertySources.from(propertySources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(environment));
    }

    @Test
    void storageDefaultsAreDisabledAndMatchContract() throws IOException {
        EkycStorageProperties storage = binder().bind("finora.ekyc.storage", EkycStorageProperties.class).get();

        assertThat(storage.isEnabled()).isFalse();
        assertThat(storage.getEndpoint()).isEmpty();
        assertThat(storage.getRegion()).isEqualTo("us-east-1");
        assertThat(storage.getBucket()).isEqualTo("finora-ekyc");
        assertThat(storage.isPathStyle()).isFalse();
        assertThat(storage.getEnv()).isEqualTo("dev");
        assertThat(storage.getRetentionDays()).isEqualTo(30);
    }

    @Test
    void consentDefaultsKeepVietnameseText() throws IOException {
        EkycConsentProperties consent = binder().bind("finora.ekyc.consent", EkycConsentProperties.class).get();

        assertThat(consent.getVersion()).isEqualTo("ekyc-2026-10");
        assertThat(consent.getContactEmail()).isEmpty();
        assertThat(consent.getStorageLocation()).isEqualTo("hệ thống lưu trữ thử nghiệm của FINORA");
        assertThat(consent.getServerLocation()).isEqualTo("Malaysia");
        assertThat(consent.getEffectiveDate()).isEqualTo("16/10/2026");
        assertThat(consent.isMentionsGemini()).isTrue();
    }

    @Test
    void aiAndMultipartSettings() throws IOException {
        Binder binder = binder();
        AiServiceProperties ai = binder.bind("finora.ai", AiServiceProperties.class).get();

        assertThat(ai.getUrl()).isEqualTo("http://localhost:8000");
        assertThat(ai.getLivenessReadTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(binder.bind("spring.servlet.multipart.max-file-size", DataSize.class).get())
                .isEqualTo(DataSize.ofMegabytes(12));
        assertThat(binder.bind("spring.servlet.multipart.max-request-size", DataSize.class).get())
                .isEqualTo(DataSize.ofMegabytes(40));
        assertThat(binder.bind("finora.ekyc.sweeper.enabled", Boolean.class).get()).isTrue();
        assertThat(binder.bind("finora.ekyc.sweeper.interval-ms", Long.class).get()).isEqualTo(3_600_000L);
    }
}
