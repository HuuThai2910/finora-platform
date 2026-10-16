package com.finora.user.config;

import com.finora.user.integration.storage.DisabledEkycEvidenceStorage;
import com.finora.user.integration.storage.EkycEvidenceStorage;
import com.finora.user.integration.storage.EkycStorageKeys;
import com.finora.user.integration.storage.S3EkycEvidenceStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chọn bản cài đặt kho lưu bằng chứng theo {@code finora.ekyc.storage.enabled}.
 * <p>
 * Tắt (mặc định) → {@link DisabledEkycEvidenceStorage}; bật → S3 client tới SeaweedFS/Amazon S3.
 * S3 client được đóng khi ứng dụng dừng (Spring tự gọi {@code close()} của bean).
 */
@Slf4j
@Configuration
public class EkycStorageConfiguration {

    @Bean
    public EkycEvidenceStorage ekycEvidenceStorage(EkycStorageProperties properties) {
        EkycStorageKeys.requireValidEnv(properties.getEnv());
        if (!properties.isEnabled()) {
            log.info("Kho lưu bằng chứng eKYC đang tắt — eKYC sẽ trả STORAGE_UNAVAILABLE");
            return new DisabledEkycEvidenceStorage();
        }
        log.info("Kho lưu bằng chứng eKYC: bucket={}, endpoint={}, pathStyle={}, env={}",
                properties.getBucket(),
                properties.getEndpoint() == null || properties.getEndpoint().isBlank()
                        ? "aws-default" : properties.getEndpoint(),
                properties.isPathStyle(), properties.getEnv());
        return new S3EkycEvidenceStorage(S3EkycEvidenceStorage.buildClient(properties), properties.getBucket());
    }
}
