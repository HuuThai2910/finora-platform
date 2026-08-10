package com.finora.loan.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LoanContractPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LoanTimeConfig.class)
            .withPropertyValues(
                    "finora.loan.pricing-disclosure-version=RATE_DISCLOSURE_V1",
                    "finora.loan.interest-rate-unit=PERCENT_PER_YEAR",
                    "finora.loan.rate-notice=Lịch dự kiến",
                    "finora.loan.contract.decision-policy-version=ADMIN_MANUAL_DECISION_V1",
                    "finora.loan.contract.terms-version=LOAN_TERMS_V1",
                    "finora.loan.contract.document-version=CLICK_WRAP_TEXT_V1",
                    "finora.loan.contract.signature-window=7d",
                    "finora.loan.contract.maximum-signature-window=30d",
                    "finora.loan.contract.expiry-batch-size=100"
            );

    @Test
    void registersAndBindsContractPolicyAsSpringBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LoanContractProperties.class);
            LoanContractProperties properties = context.getBean(LoanContractProperties.class);
            assertThat(properties.signatureWindow()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.maximumSignatureWindow()).isEqualTo(Duration.ofDays(30));
            assertThat(properties.expiryBatchSize()).isEqualTo(100);
        });
    }
}
