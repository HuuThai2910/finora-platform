package com.finora.payment.integration.fineract;

import com.finora.common.exception.BusinessException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * Chuyển lỗi khi đọc nghĩa vụ/báo giá từ Fineract thành lỗi nghiệp vụ có mã rõ ràng.
 *
 * <p>Các lệnh đọc này chạy trước khi Payment trừ tiền, nên lỗi ở đây không làm lệch ledger; trước đây lỗi thô rơi vào
 * {@code 500 INTERNAL_ERROR} và client không phân biệt được “không có kỳ đến hạn” với “core tạm thời lỗi”.</p>
 */
public final class FineractQuoteErrors {

    private FineractQuoteErrors() {
    }

    public static <T> T read(Supplier<T> call) {
        try {
            return call.get();
        } catch (FineractRepaymentException exception) {
            throw translate(exception);
        }
    }

    static BusinessException translate(FineractRepaymentException exception) {
        if ("FINERACT_NO_SCHEDULED_DUE".equals(exception.code())) {
            return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, exception.code(), exception.getMessage());
        }
        if (exception.outcomeUnknown()) {
            return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FINERACT_REPAYMENT_QUOTE_UNAVAILABLE",
                    "Chưa đọc được nghĩa vụ trả nợ từ hệ thống lõi, vui lòng thử lại sau");
        }
        return new BusinessException(HttpStatus.BAD_GATEWAY, "FINERACT_REPAYMENT_QUOTE_REJECTED",
                "Hệ thống lõi từ chối truy vấn nghĩa vụ trả nợ; cần đối soát khoản vay");
    }
}
