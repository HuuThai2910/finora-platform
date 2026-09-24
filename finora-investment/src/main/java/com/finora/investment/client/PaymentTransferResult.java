package com.finora.investment.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Kết quả chuyển tiền giữa hai ví cho một giao dịch chuyển nhượng Note.
 *
 * <p>Tách khỏi {@link PaymentHoldResult} vì hai việc khác nhau: giữ tiền trả về mã giữ chỗ
 * còn nhả được, còn chuyển tiền trả về mã giao dịch đã hoàn tất và không hoàn lại. Dùng chung
 * một lớp sẽ khiến nơi gọi phải đoán {@code holdReference} đang mang nghĩa nào.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransferResult {

    private boolean success;

    /** Mã giao dịch phía Payment, lưu lại để đối soát. */
    private String transferReference;

    private String errorCode;
    private String errorMessage;

    /** Lỗi hạ tầng tạm thời thì gọi lại được; lỗi nghiệp vụ thì không. */
    private boolean retryable;

    public static PaymentTransferResult ok(String transferReference) {
        return PaymentTransferResult.builder()
                .success(true)
                .transferReference(transferReference)
                .build();
    }

    public static PaymentTransferResult rejected(String errorCode, String errorMessage) {
        return PaymentTransferResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    public static PaymentTransferResult unavailable(String errorMessage) {
        return PaymentTransferResult.builder()
                .success(false)
                .errorCode("PAYMENT_UNAVAILABLE")
                .errorMessage(errorMessage)
                .retryable(true)
                .build();
    }
}
