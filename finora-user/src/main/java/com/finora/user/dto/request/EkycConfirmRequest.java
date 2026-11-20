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
 * <p>
 * {@code reportIncorrect=true}: người dùng đã chụp lại nhiều lần mà vẫn thấy thông tin đọc từ thẻ chưa đúng — server bỏ
 * qua {@code edits} và luôn chuyển hồ sơ cho nhân viên duyệt (số lần do app đếm, server không kiểm).
 *
 * @param edits           tên trường (camelCase như bản nháp) → giá trị mới, tối đa 500 ký tự
 * @param reportIncorrect gửi hồ sơ cho nhân viên vì thông tin đọc từ thẻ chưa đúng; {@code null} như {@code false}
 */
public record EkycConfirmRequest(
        Map<@NotBlank String, @NotBlank @Size(max = 500) String> edits,
        Boolean reportIncorrect
) {

    public boolean reportsIncorrect() {
        return Boolean.TRUE.equals(reportIncorrect);
    }
}
