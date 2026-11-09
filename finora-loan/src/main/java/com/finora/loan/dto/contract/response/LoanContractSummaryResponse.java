package com.finora.loan.dto.contract.response;

import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.domain.contract.enums.LoanContractStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record LoanContractSummaryResponse(
        String contractNumber,
        String applicationNumber,
        BigDecimal principalAmount,
        Integer termMonths,
        BigDecimal annualInterestRate,
        BigDecimal totalRepayment,
        LoanContractStatus status,
        Instant expiresAt,
        Long version,
        Instant createdAt,
        /** Mã hiển thị {@code HĐ-yyyyMMdd-XXXX} cho người dùng; {@code contractNumber} vẫn là khóa tra cứu API. */
        String contractCode,
        /** Mã hiển thị {@code HS-...} của hồ sơ gốc. */
        String applicationCode,
        /** Mục đích vay của hồ sơ để danh sách hiện dòng tóm tắt thay cho mã. */
        LoanPurpose purposeCode
) {
}
