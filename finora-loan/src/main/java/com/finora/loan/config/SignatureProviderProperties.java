package com.finora.loan.config;

import com.finora.loan.domain.contract.SignatureProviderType;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình chọn provider phía server; secret SmartCA chỉ được truyền qua môi trường. */
@ConfigurationProperties(prefix = "finora.signature")
public record SignatureProviderProperties(
        SignatureProviderType provider,
        VnptSmartCa vnptSmartCa
) {
    public SignatureProviderProperties {
        provider = provider == null ? SignatureProviderType.MOCK : provider;
        if (provider == SignatureProviderType.VNPT_SMART_CA) {
            if (vnptSmartCa == null) {
                throw new IllegalArgumentException("Thiếu cấu hình finora.signature.vnpt-smart-ca");
            }
            vnptSmartCa.validateRequired();
        }
    }

    public record VnptSmartCa(
            URI baseUrl,
            String serviceProviderId,
            String serviceProviderPassword,
            boolean sandboxFixedSignerEnabled,
            String sandboxUserId,
            String sandboxSerialNumber,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        public VnptSmartCa {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(15) : readTimeout;
        }

        private void validateRequired() {
            if (baseUrl == null || !"https".equalsIgnoreCase(baseUrl.getScheme())) {
                throw new IllegalArgumentException("SmartCA baseUrl phải dùng HTTPS");
            }
            requireSecret(serviceProviderId, "serviceProviderId");
            requireSecret(serviceProviderPassword, "serviceProviderPassword");
            if (!sandboxFixedSignerEnabled) {
                throw new IllegalArgumentException(
                        "SmartCA hiện chỉ hỗ trợ UAT fixed signer; production identity contract chưa sẵn sàng");
            }
            if (!"rmgateway.vnptit.vn".equalsIgnoreCase(baseUrl.getHost())) {
                throw new IllegalArgumentException("SmartCA fixed signer chỉ được phép gọi UAT rmgateway.vnptit.vn");
            }
            requireSecret(sandboxUserId, "sandboxUserId");
            requireSecret(sandboxSerialNumber, "sandboxSerialNumber");
            if (connectTimeout.isNegative() || connectTimeout.isZero()
                    || readTimeout.isNegative() || readTimeout.isZero()) {
                throw new IllegalArgumentException("SmartCA timeout phải dương");
            }
        }

        private static void requireSecret(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Thiếu SmartCA " + field);
            }
        }
    }
}
