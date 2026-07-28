package com.finora.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response chuẩn cho TẤT CẢ API trong hệ thống FINORA.
 * Mọi Controller đều phải trả về đối tượng này để Frontend xử lý đồng nhất.
 *
 * @param <T> Kiểu dữ liệu của phần data trả về
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BaseResponse<T> {

    private int code;
    private String message;
    private T data;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // === Factory Methods ===

    public static <T> BaseResponse<T> success(T data) {
        return BaseResponse.<T>builder()
                .code(200)
                .message("Thành công")
                .data(data)
                .build();
    }

    public static <T> BaseResponse<T> success(String message, T data) {
        return BaseResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> BaseResponse<T> created(T data) {
        return BaseResponse.<T>builder()
                .code(201)
                .message("Tạo mới thành công")
                .data(data)
                .build();
    }

    public static <T> BaseResponse<T> error(int code, String message) {
        return BaseResponse.<T>builder()
                .code(code)
                .message(message)
                .build();
    }
}
