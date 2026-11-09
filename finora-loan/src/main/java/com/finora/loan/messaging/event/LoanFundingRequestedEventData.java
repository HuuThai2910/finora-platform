package com.finora.loan.messaging.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Payload v1 tối thiểu để Investment tạo market projection, không chứa PII/KYC. */
public record LoanFundingRequestedEventData(
        Long loanApplicationId,
        String applicationNumber,
        Integer listingVersion,
        Integer fundingRound,
        String productCode,
        String purpose,
        String borrowerRegion,
        String creditGrade,
        Integer creditScore,
        String targetAmount,
        String annualInterestRate,
        Integer termMonths,
        String repaymentMethod,
        String termsVersion,
        String termsHash,
        /**
         * ID logic người vay (claim {@code user_id}), không phải PII. Investment dùng để chặn
         * người vay tự góp vốn khoản của mình. Trường bổ sung v1: producer cũ để null.
         */
        String borrowerId,
        /**
         * Mã hiển thị {@code HS-yyyyMMdd-XXXX} của hồ sơ để Investment hiện cho nhà đầu tư; khóa đối chiếu vẫn là
         * {@code applicationNumber}. Trường bổ sung v1: producer cũ không gửi, consumer cũ bỏ qua.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL) String applicationCode,
        /**
         * Mã {@code LoanPurpose} ({@code EDUCATION}, {@code SMALL_BUSINESS}…) để app chọn biểu tượng theo mục đích;
         * {@code purpose} vẫn là nhãn hiển thị. Trường bổ sung v1: producer cũ không gửi, consumer cũ bỏ qua.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL) String purposeCode
) {
}
