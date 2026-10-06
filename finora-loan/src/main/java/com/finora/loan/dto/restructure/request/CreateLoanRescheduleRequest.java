package com.finora.loan.dto.restructure.request;

import com.finora.loan.domain.restructure.LoanRescheduleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateLoanRescheduleRequest(
        @NotNull LoanRescheduleType requestType,
        @NotNull LocalDate rescheduleFromDate,
        LocalDate adjustedDueDate,
        @Positive @Max(120) Integer extraTerms,
        @NotBlank @Size(max = 500) String reasonComment,
        @NotBlank @Size(max = 50) String confirmedTermsVersion
) {}

