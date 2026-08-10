package com.finora.loan.integration.fineract.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.loan.integration.fineract.client.FineractIntegrationException;
import com.finora.loan.integration.fineract.contract.FineractProductConfiguration;
import com.finora.loan.integration.fineract.contract.FineractProductCreationResult;
import com.finora.loan.support.HashingService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Chỉ ánh xạ contract liên quan đến Loan Product giữa FINORA và Fineract. */
@Component
@RequiredArgsConstructor
public class FineractProductPayloadMapper {

    private final HashingService hashingService;
    private final FineractRepaymentPolicy repaymentPolicy;

    /** Ánh xạ Product FINORA sang allowlist Fineract 1.15; MVP chưa bật accounting. */
    public Map<String, Object> toCreateProductPayload(FineractProductConfiguration source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", fineractName(source));
        payload.put("shortName", shortName(source.loanProductId(), source.finoraProductVersion()));
        payload.put("description", "FINORA product " + source.code() + " / " + source.externalId());
        payload.put("externalId", source.externalId());
        payload.put("currencyCode", "VND");
        payload.put("digitsAfterDecimal", 0);
        payload.put("inMultiplesOf", 1000);
        payload.put("principal", source.minAmount().add(source.maxAmount())
                .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP));
        payload.put("minPrincipal", source.minAmount());
        payload.put("maxPrincipal", source.maxAmount());
        payload.put("numberOfRepayments", source.minTermMonths());
        payload.put("minNumberOfRepayments", source.minTermMonths());
        payload.put("maxNumberOfRepayments", source.maxTermMonths());
        payload.put("repaymentEvery", 1);
        payload.put("repaymentFrequencyType", 2);
        payload.put("interestRatePerPeriod", source.annualInterestRate());
        payload.put("interestRateFrequencyType", 3);
        payload.put("amortizationType", repaymentPolicy.amortizationType(source.repaymentMethod()));
        payload.put("interestType", repaymentPolicy.interestType());
        payload.put("interestCalculationPeriodType", repaymentPolicy.interestCalculationPeriodType());
        payload.put("transactionProcessingStrategyCode", "mifos-standard-strategy");
        payload.put("accountingRule", 1);
        payload.put("isInterestRecalculationEnabled", false);
        payload.put("daysInYearType", 1);
        payload.put("daysInMonthType", 1);
        payload.put("dateFormat", "yyyy-MM-dd");
        payload.put("locale", "en");
        return payload;
    }

    /** Chỉ lưu resourceId đã kiểm tra, không lưu raw response có thể thay đổi hoặc chứa dữ liệu ngoài allowlist. */
    public String sanitizedProductResponse(JsonNode response) {
        long resourceId = response == null ? 0L : response.path("resourceId").asLong(0L);
        if (resourceId <= 0) {
            throw contractMismatch("Fineract không trả resourceId Product hợp lệ");
        }
        return hashingService.toJson(Map.of("resourceId", resourceId));
    }

    /** Đối chiếu exact external ID để retry timeout không tạo Product logic thứ hai. */
    public Optional<FineractProductCreationResult> findProductByExternalId(JsonNode products, String externalId) {
        if (products == null || !products.isArray()) {
            throw contractMismatch("Fineract không trả danh sách Product hợp lệ khi đối chiếu");
        }
        for (JsonNode product : products) {
            if (!externalId.equals(product.path("externalId").asText())) {
                continue;
            }
            long resourceId = product.path("id").asLong(0L);
            if (resourceId <= 0) {
                throw contractMismatch("Fineract trả Product đối chiếu nhưng thiếu ID hợp lệ");
            }
            String snapshot = hashingService.toJson(Map.of(
                    "resourceId", resourceId,
                    "externalId", externalId,
                    "reconciled", true
            ));
            return Optional.of(new FineractProductCreationResult(resourceId, snapshot));
        }
        return Optional.empty();
    }

    private String fineractName(FineractProductConfiguration source) {
        String name = "FINORA " + source.code() + " v" + source.finoraProductVersion();
        return name.length() <= 100 ? name : name.substring(0, 100);
    }

    private String shortName(Long productId, Long productVersion) {
        long value = Math.abs((productId * 31L) + productVersion);
        String code = Long.toString(value, 36).toUpperCase();
        return ("F" + code).substring(0, Math.min(4, code.length() + 1));
    }

    private FineractIntegrationException contractMismatch(String message) {
        return new FineractIntegrationException("FINERACT_CONTRACT_MISMATCH", message, false, null);
    }
}
