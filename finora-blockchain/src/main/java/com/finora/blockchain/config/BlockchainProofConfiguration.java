package com.finora.blockchain.config;

import com.finora.blockchain.domain.proof.ProofProviderType;
import com.finora.blockchain.integration.proof.HyperledgerFabricProofLedger;
import com.finora.blockchain.integration.proof.MockProofLedger;
import com.finora.blockchain.integration.proof.ProofLedger;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProofSubmissionProperties.class)
public class BlockchainProofConfiguration {

    @Bean
    Clock blockchainClock() {
        return Clock.systemUTC();
    }

    @Bean
    ProofLedger proofLedger(ProofSubmissionProperties properties) {
        if (properties.provider() == ProofProviderType.HYPERLEDGER_FABRIC) {
            return new HyperledgerFabricProofLedger();
        }
        return new MockProofLedger();
    }
}
