package com.finora.loan.service.contract.impl;

import com.finora.loan.repository.contract.LoanContractRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractNumberGeneratorImplTest {

    private final LoanContractRepository repository = mock(LoanContractRepository.class);
    private final ContractNumberGeneratorImpl generator = new ContractNumberGeneratorImpl(repository);

    @Test
    void lookupNumberKeepsTheRandomHexFormat() {
        assertThat(generator.next()).matches("LC-[0-9A-F]{20}");
    }

    @Test
    void displayCodeUsesVietnamDateAndRetriesOnCollision() {
        when(repository.existsByContractCode(anyString())).thenReturn(true, false);

        // 18:00 UTC ngày 09/11 là 01:00 ngày 10/11 giờ Việt Nam.
        String code = generator.nextCode(Instant.parse("2026-11-09T18:00:00Z"));

        assertThat(code).matches("HĐ-20261110-[ABCDEFGHJKMNPQRSTWXYZ2-9]{4}");
        verify(repository, times(2)).existsByContractCode(anyString());
    }

    @Test
    void stopsAfterTooManyCollisions() {
        when(repository.existsByContractCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> generator.nextCode(Instant.parse("2026-11-09T03:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }
}
