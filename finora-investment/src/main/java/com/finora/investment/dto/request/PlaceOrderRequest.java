package com.finora.investment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Lệnh đặt vốn của nhà đầu tư.
 *
 * <p>Số tiền dùng {@link BigDecimal} và giới hạn 2 chữ số thập phân theo chuẩn tiền tệ
 * của hệ thống; kiểm tra chia hết cho mệnh giá Note thuộc về domain vì cần biết listing.</p>
 *
 * @param amount số tiền muốn đầu tư, đơn vị VND
 */
public record PlaceOrderRequest(
        @NotNull(message = "Số tiền đầu tư không được để trống")
        @DecimalMin(value = "1", message = "Số tiền đầu tư phải lớn hơn 0")
        @Digits(integer = 16, fraction = 2, message = "Số tiền đầu tư không hợp lệ")
        BigDecimal amount,
        /** Phiên bản công bố rủi ro nhà đầu tư vừa đọc và xác nhận; bắt buộc với lệnh sơ cấp do nhà đầu tư tự đặt. */
        @Size(max = 50, message = "Phiên bản công bố rủi ro không hợp lệ")
        String riskAcknowledgementVersion
) {
    /** Lệnh hệ thống đặt thay nhà đầu tư (Auto-Invest) không có thao tác xác nhận trên màn hình. */
    public PlaceOrderRequest(BigDecimal amount) {
        this(amount, null);
    }
}
