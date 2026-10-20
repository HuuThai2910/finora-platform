package com.finora.common.security.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Giới hạn số lần một người gọi được dùng endpoint trong một khoảng thời gian (D40, W3A). Dùng cho thao tác tốn tiền
 * hoặc gọi provider bên ngoài: OCR CCCD (Gemini), tạo lệnh nạp (ZaloPay), báo giá trả nợ (Fineract).
 * <p>
 * Đếm theo người dùng (claim {@code sub}); request chưa đăng nhập đếm theo địa chỉ IP. Vượt giới hạn trả
 * {@code 429 RATE_LIMITED} kèm header {@code Retry-After}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    /** Tên nhóm giới hạn, ví dụ {@code ekyc.ocr}; các endpoint cùng tên dùng chung một bộ đếm. */
    String value();

    /** Số lần tối đa trong một cửa sổ. */
    int limit();

    /** Độ dài cửa sổ theo ISO-8601, ví dụ {@code PT10M}. */
    String window();
}
