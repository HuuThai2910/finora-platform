package com.finora.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Xác nhận bản nháp eKYC, kèm sửa đổi tuỳ chọn.
 * <p>
 * Chỉ trường đang có {@code editable=true} (văn bản tự do mà chỉ AI đọc ra) mới được sửa; tên trường
 * khác — kể cả {@code idNumber} — bị từ chối {@code 400 EDIT_NOT_ALLOWED}. Dùng {@code Map} thay vì
 * bản ghi cố định để không âm thầm bỏ qua trường lạ.
 *
 * @param edits tên trường (camelCase như bản nháp) → giá trị mới, tối đa 500 ký tự
 */
public record EkycConfirmRequest(
        Map<@NotBlank String, @NotBlank @Size(max = 500) String> edits
) {
}
