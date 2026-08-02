package com.finora.common.dto;

/**
 * Mô tả một field không hợp lệ mà không làm lộ giá trị người dùng đã gửi.
 *
 * @param field tên field theo DTO tại biên API
 * @param reason lý do validation thất bại
 */
public record FieldViolation(String field, String reason) {
}
