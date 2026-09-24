package com.finora.investment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
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
        BigDecimal amount
) {
}
