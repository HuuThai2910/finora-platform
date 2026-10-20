package com.finora.payment.integration.fineract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FineractQuoteErrorsTest {

    @Test
    void unavailableCoreBecomesRetryableServiceUnavailable() {
        assertThatThrownBy(() -> FineractQuoteErrors.read(() -> {
            throw new FineractRepaymentException("FINERACT_DUE_UNAVAILABLE", "timeout", true, null);
        })).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(exception.getCode()).isEqualTo("FINERACT_REPAYMENT_QUOTE_UNAVAILABLE");
        });
    }

    @Test
    void nothingDueIsABusinessRuleNotAServerError() {
        assertThatThrownBy(() -> FineractQuoteErrors.read(() -> {
            throw new FineractRepaymentException("FINERACT_NO_SCHEDULED_DUE", "Không có nghĩa vụ", false, null);
        })).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(exception.getCode()).isEqualTo("FINERACT_NO_SCHEDULED_DUE");
        });
    }

    @Test
    void rejectedQueryIsReportedAsCoreRejection() {
        assertThatThrownBy(() -> FineractQuoteErrors.read(() -> {
            throw new FineractRepaymentException("FINERACT_DUE_REJECTED", "HTTP 404", false, null);
        })).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(exception.getCode()).isEqualTo("FINERACT_REPAYMENT_QUOTE_REJECTED");
        });
    }

    @Test
    void successfulReadPassesValueThrough() {
        assertThat(FineractQuoteErrors.read(() -> "quote")).isEqualTo("quote");
    }
}
