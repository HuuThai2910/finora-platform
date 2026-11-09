package com.finora.loan.service.application.impl;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;
import com.finora.loan.repository.application.LoanApplicationRepository;
import com.finora.loan.repository.contract.LoanContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ô "Mở hồ sơ theo mã" của web nhận mọi mã nhân viên có trong tay và đổi về mã tra cứu {@code LA-}. */
class ApplicationNumberLookupServiceImplTest {

    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final LoanContractRepository contracts = mock(LoanContractRepository.class);
    private final ApplicationNumberLookupServiceImpl service =
            new ApplicationNumberLookupServiceImpl(applications, contracts);
    private final LoanApplication application = mock(LoanApplication.class);

    @BeforeEach
    void setUp() {
        when(application.getId()).thenReturn(7L);
        when(application.getApplicationNumber()).thenReturn("LA-0123456789ABCDEF0123");
        when(application.getApplicationCode()).thenReturn("HS-20261109-3RXA");
    }

    @Test
    void resolvesApplicationDisplayCodeTypedInLowerCase() {
        when(applications.findByApplicationCode("HS-20261109-3RXA")).thenReturn(Optional.of(application));

        ApplicationNumberResponse result = service.resolve(" hs-20261109-3rxa ");

        assertThat(result.applicationNumber()).isEqualTo("LA-0123456789ABCDEF0123");
        assertThat(result.applicationCode()).isEqualTo("HS-20261109-3RXA");
    }

    @Test
    void resolvesContractDisplayCodeTypedWithoutAccent() {
        when(contracts.findApplicationIdByContractCode("HĐ-20261109-K7QM")).thenReturn(Optional.of(7L));
        when(applications.findById(7L)).thenReturn(Optional.of(application));

        LoanContractRepository.ContractCodeView reference = org.mockito.Mockito.mock(
                LoanContractRepository.ContractCodeView.class);
        when(reference.getContractNumber()).thenReturn("LC-ABCDEF0123456789ABCD");
        when(reference.getContractCode()).thenReturn("HĐ-20261109-K7QM");
        when(contracts.findContractReferenceByApplicationId(7L)).thenReturn(Optional.of(reference));

        ApplicationNumberResponse result = service.resolve("HD-20261109-K7QM");

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.contractNumber()).isEqualTo("LC-ABCDEF0123456789ABCD");
        assertThat(result.contractCode()).isEqualTo("HĐ-20261109-K7QM");
    }

    @Test
    void stillResolvesOldLookupNumbers() {
        when(applications.findByApplicationNumber("LA-0123456789ABCDEF0123")).thenReturn(Optional.of(application));
        when(contracts.findApplicationIdByContractNumber("LC-ABCDEF0123456789ABCD")).thenReturn(Optional.of(7L));
        when(applications.findById(7L)).thenReturn(Optional.of(application));

        assertThat(service.resolve("la-0123456789abcdef0123").id()).isEqualTo(7L);
        assertThat(service.resolve("LC-ABCDEF0123456789ABCD").id()).isEqualTo(7L);
    }

    @Test
    void unknownCodeIsNotFound() {
        when(applications.findByApplicationCode("HS-20261109-ZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("HS-20261109-ZZZZ")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.resolve("   ")).isInstanceOf(ResourceNotFoundException.class);
    }
}
