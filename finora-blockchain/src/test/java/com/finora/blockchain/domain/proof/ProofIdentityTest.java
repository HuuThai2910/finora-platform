package com.finora.blockchain.domain.proof;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProofIdentityTest {

    @Test
    void sameMilestoneAlwaysMapsToTheSameVersionFiveProofId() {
        UUID first = ProofIdentity.forMilestone(ProofType.CONTRACT_DOCUMENT, "LoanContract", "LC-2026-000123");
        UUID again = ProofIdentity.forMilestone(ProofType.CONTRACT_DOCUMENT, "LoanContract", "LC-2026-000123");

        assertThat(again).isEqualTo(first);
        assertThat(first.version()).isEqualTo(5);
        assertThat(first.variant()).isEqualTo(2);
    }

    @Test
    void matchesAnIndependentUuidFiveImplementation() {
        // Giá trị tính bằng uuid.uuid5 của Python với cùng namespace và tên "<proofType>|<aggregateType>|<id>".
        assertThat(ProofIdentity.forMilestone(ProofType.CONTRACT_DOCUMENT, "LoanContract", "LC-2026-000123"))
                .isEqualTo(UUID.fromString("fffddeea-029f-5526-b15b-ecc6a16fa12c"));
        assertThat(ProofIdentity.forMilestone(ProofType.REPAYMENT, "Repayment", "7c1e1f0a-0d8b-4f55-9a52-2c3c3a8f4d10"))
                .isEqualTo(UUID.fromString("eeb5c59b-7ae6-5331-afe1-fc3fc2c91cd1"));
    }

    @Test
    void differentMilestonesOfTheSameAggregateGetDifferentIds() {
        UUID contract = ProofIdentity.forMilestone(ProofType.CONTRACT_DOCUMENT, "LoanContract", "LC-1");
        UUID disbursement = ProofIdentity.forMilestone(ProofType.DISBURSEMENT, "LoanContract", "LC-1");

        assertThat(contract).isNotEqualTo(disbursement);
    }
}
