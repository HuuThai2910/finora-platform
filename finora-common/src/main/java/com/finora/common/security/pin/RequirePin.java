package com.finora.common.security.pin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Đánh dấu endpoint chỉ chạy khi request mang pin-token hợp lệ ở header
 * {@value PinTokenService#HEADER_NAME}.
 * <p>
 * Token do finora-user cấp sau khi người dùng nhập đúng mã PIN; service gắn
 * annotation tự kiểm chữ ký mà không gọi sang finora-user, xem
 * {@link RequirePinInterceptor}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePin {

    PinScope value();
}
