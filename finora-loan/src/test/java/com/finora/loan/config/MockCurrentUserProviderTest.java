package com.finora.loan.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockCurrentUserProviderTest {

    @Test
    void shouldReturnBothFixedTemporaryActors() {
        MockCurrentUserProvider provider = new MockCurrentUserProvider();

        assertThat(provider.adminUserId()).isEqualTo("ADMIN-001");
        assertThat(provider.borrowerUserId()).isEqualTo("BORROWER-001");
    }
}
