package com.finora.investment.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Yêu cầu tạo niêm yết gọi vốn từ khoản vay đã được duyệt.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateListingRequest {
    private Long loanId;
    private String contractNumber;
    private String applicationNumber;
    /** Mã hiển thị {@code HS-...} của hồ sơ; null với event cũ. */
    private String applicationCode;
    private Integer listingVersion;
    private Integer fundingRound;
    private String termsVersion;
    private String termsHash;
    private String productCode;
    private String purpose;
    /** Mã {@code LoanPurpose} của Loan; null với event cũ. */
    private String purposeCode;
    private String region;
    private String creditGrade;
    private Integer creditScore;
    private BigDecimal targetAmount;
    private BigDecimal annualInterestRate;
    private Integer termMonths;
    private String repaymentMethod;
    /** ID logic người vay; null với listing tạo từ event cũ. */
    private String borrowerId;
}
