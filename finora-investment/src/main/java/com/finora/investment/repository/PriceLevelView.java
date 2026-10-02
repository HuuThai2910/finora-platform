package com.finora.investment.repository;

/**
 * Một mức giá của sổ lệnh: tổng số Note còn chờ khớp ở giá đó và số lệnh góp vào.
 *
 * <p>Gộp theo mức giá thay vì trả từng lệnh, để client chỉ thấy độ sâu thị trường chứ không thấy
 * ai đặt lệnh nào.</p>
 */
public record PriceLevelView(Integer pricePermille, Long quantity, Long orderCount) {
}
