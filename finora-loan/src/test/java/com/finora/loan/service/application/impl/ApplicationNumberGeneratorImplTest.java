package com.finora.loan.service.application.impl;

import com.finora.loan.repository.application.LoanApplicationRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationNumberGeneratorImplTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-11-09T02:15:00Z");

    private final LoanApplicationRepository repository = mock(LoanApplicationRepository.class);
    private final ApplicationNumberGeneratorImpl generator = new ApplicationNumberGeneratorImpl(repository);

    @Test
    void lookupNumberKeepsTheRandomHexFormat() {
        assertThat(generator.next()).matches("LA-[0-9A-F]{20}");
    }

    @Test
    void displayCodeIsChosenAgainWhenAlreadyUsed() {
        when(repository.existsByApplicationCode(anyString())).thenReturn(true, true, false);

        String code = generator.nextCode(SUBMITTED_AT);

        assertThat(code).matches("HS-20261109-[ABCDEFGHJKMNPQRSTWXYZ2-9]{4}");
        verify(repository, times(3)).existsByApplicationCode(anyString());
    }

    @Test
    void stopsInsteadOfWritingADuplicateAfterTooManyCollisions() {
        when(repository.existsByApplicationCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> generator.nextCode(SUBMITTED_AT)).isInstanceOf(IllegalStateException.class);
        verify(repository, times(ApplicationNumberGeneratorImpl.MAX_CODE_ATTEMPTS)).existsByApplicationCode(anyString());
    }
}
