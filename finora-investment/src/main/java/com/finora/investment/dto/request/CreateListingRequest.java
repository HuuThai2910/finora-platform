package com.finora.investment.dto.request;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    private String productCode;
    private String purpose;
    private String region;
    private String creditGrade;
    private Integer creditScore;
    private BigDecimal targetAmount;
    private BigDecimal annualInterestRate;
    private Integer termMonths;
    private String repaymentMethod;
}
