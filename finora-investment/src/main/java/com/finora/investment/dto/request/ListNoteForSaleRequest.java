package com.finora.investment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Yêu cầu treo một Note lên chợ thứ cấp.
 *
 * <p>Chỉ nhận giá: Note nào thì lấy từ đường dẫn, còn người bán lấy từ token. Trần giá bằng dư
 * nợ gốc còn lại kiểm ở domain vì cần đọc Note.</p>
 *
 * @param askingPrice giá người bán muốn nhận, đơn vị VND
 */
public record ListNoteForSaleRequest(
        @NotNull(message = "Giá bán không được để trống")
        @DecimalMin(value = "1", message = "Giá bán phải lớn hơn 0")
        @Digits(integer = 16, fraction = 2, message = "Giá bán không hợp lệ")
        BigDecimal askingPrice
) {
}
