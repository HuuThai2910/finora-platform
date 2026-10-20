package com.finora.blockchain.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kết nối Fabric Gateway (W3D, D53). Chỉ được đọc khi {@code finora.blockchain.proof.provider=HYPERLEDGER_FABRIC};
 * file khóa/chứng thư do docker/fabric-network sinh và nằm ngoài Git, ở đây chỉ giữ đường dẫn.
 */
@ConfigurationProperties(prefix = "finora.blockchain.fabric")
public record FabricGatewayProperties(
        String peerEndpoint,
        String peerHostOverride,
        String mspId,
        String channelName,
        String chaincodeName,
        String certPath,
        String keyPath,
        String tlsCertPath,
        Duration evaluateTimeout,
        Duration endorseTimeout,
        Duration submitTimeout,
        Duration commitStatusTimeout
) {
    public FabricGatewayProperties {
        peerEndpoint = blankToDefault(peerEndpoint, "localhost:7051");
        peerHostOverride = blankToDefault(peerHostOverride, null);
        mspId = blankToDefault(mspId, "Org1MSP");
        channelName = blankToDefault(channelName, "finora-channel");
        chaincodeName = blankToDefault(chaincodeName, "finora-ledger");
        certPath = blankToDefault(certPath, null);
        keyPath = blankToDefault(keyPath, null);
        tlsCertPath = blankToDefault(tlsCertPath, null);
        evaluateTimeout = positiveOrDefault(evaluateTimeout, Duration.ofSeconds(5), "evaluateTimeout");
        endorseTimeout = positiveOrDefault(endorseTimeout, Duration.ofSeconds(15), "endorseTimeout");
        submitTimeout = positiveOrDefault(submitTimeout, Duration.ofSeconds(5), "submitTimeout");
        commitStatusTimeout = positiveOrDefault(commitStatusTimeout, Duration.ofSeconds(60), "commitStatusTimeout");
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("fabric." + name + " phải dương");
        }
        return value;
    }
}
