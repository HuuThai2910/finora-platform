package com.finora.blockchain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.enums.ProofProviderType;
import com.finora.blockchain.integration.fabric.client.FabricLedgerClient;
import com.finora.blockchain.integration.fabric.client.ProofLedger;
import com.finora.blockchain.integration.fabric.client.impl.FabricProofLedger;
import com.finora.blockchain.integration.fabric.client.impl.MockProofLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({ProofSubmissionProperties.class, FabricGatewayProperties.class})
public class BlockchainProofConfiguration {

    @Bean
    Clock blockchainClock() {
        return Clock.systemUTC();
    }

    /** MOCK là mặc định; Fabric chỉ khi operator chọn rõ và không bao giờ âm thầm rơi về MOCK khi Fabric lỗi. */
    @Bean
    ProofLedger proofLedger(
            ProofSubmissionProperties properties,
            ObjectProvider<FabricLedgerClient> fabricClient,
            ObjectMapper mapper
    ) {
        if (properties.provider() == ProofProviderType.HYPERLEDGER_FABRIC) {
            return new FabricProofLedger(fabricClient.getObject(), mapper);
        }
        return new MockProofLedger();
    }
}
