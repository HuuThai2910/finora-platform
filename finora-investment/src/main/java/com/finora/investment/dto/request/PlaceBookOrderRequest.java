package com.finora.investment.dto.request;

import com.finora.investment.domain.orderbook.OrderSide;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Lệnh giới hạn trên sổ lệnh Notes.
 *
 * @param side               {@code BID} mua, {@code ASK} bán
 * @param pricePercent       giá theo % dư nợ gốc còn lại, bước 0,1%, từ 0,1 tới 100. Trần 100%
 *                           để người mua không bao giờ trả quá phần gốc sẽ nhận về (INV-E1 mục 3)
 * @param quantity           số Note
 * @param acknowledgeDefault bắt buộc {@code true} khi khoản vay đang nợ xấu: người đặt xác nhận đã
 *                           đọc cảnh báo, để cảnh báo không chỉ nằm ở giao diện (INV-E1 mục 8.2)
 */
public record PlaceBookOrderRequest(
        @NotNull(message = "Chưa chọn chiều mua hay bán")
        OrderSide side,

        @NotNull(message = "Giá không được để trống")
        @DecimalMin(value = "0.1", message = "Giá tối thiểu là 0,1% dư nợ gốc")
        @DecimalMax(value = "100", message = "Giá không được vượt 100% dư nợ gốc")
        @Digits(integer = 3, fraction = 1, message = "Giá chỉ có một chữ số thập phân (bước 0,1%)")
        BigDecimal pricePercent,

        @NotNull(message = "Số Note không được để trống")
        @Min(value = 1, message = "Phải đặt ít nhất 1 Note")
        @Max(value = 10000, message = "Một lệnh đặt tối đa 10.000 Note")
        Integer quantity,

        Boolean acknowledgeDefault
) {
}
