package com.finora.user.dto.request;

import com.finora.common.enums.user.Gender;
import com.finora.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

/**
 * Bộ lọc nâng cao của danh sách người dùng quản trị (bảng lọc trang Phân quyền và trang Khách hàng). Mọi trường tùy
 * chọn; {@code null} là không lọc. Ngày tính theo giờ Việt Nam và gồm cả hai đầu khoảng.
 *
 * @param createdFrom   ngày tạo tài khoản (ngày đăng ký) từ
 * @param createdTo     ngày tạo tài khoản đến
 * @param locked        {@code true} chỉ tài khoản đang khóa, {@code false} chỉ tài khoản đang hoạt động — theo lần khóa
 *                      ghi ở hồ sơ ({@code locked_at}); Keycloak vẫn là nơi chặn đăng nhập thật
 * @param ageMin        tuổi tối thiểu (theo ngày sinh đọc từ eKYC; tài khoản chưa có ngày sinh bị loại khi lọc tuổi)
 * @param ageMax        tuổi tối đa
 * @param gender        giới tính (theo eKYC)
 * @param lastLoginFrom lần đăng nhập gần nhất (mọi thiết bị) từ ngày
 * @param lastLoginTo   lần đăng nhập gần nhất đến ngày
 */
public record UserListFilter(
        LocalDate createdFrom,
        LocalDate createdTo,
        Boolean locked,
        Integer ageMin,
        Integer ageMax,
        Gender gender,
        LocalDate lastLoginFrom,
        LocalDate lastLoginTo) {

    /** Tuổi tối đa nhận khi lọc — chặn số vô lý làm tràn phép trừ năm. */
    static final int MAX_AGE = 150;

    public static final UserListFilter NONE = new UserListFilter(null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return createdFrom == null && createdTo == null && locked == null && ageMin == null && ageMax == null
                && gender == null && lastLoginFrom == null && lastLoginTo == null;
    }

    /** Khoảng sai trả 400 {@code FILTER_RANGE_INVALID}, tuổi âm hoặc quá lớn trả 400 {@code FILTER_VALUE_INVALID}. */
    public UserListFilter validate() {
        requireAge(ageMin, "Tuổi từ");
        requireAge(ageMax, "Tuổi đến");
        requireOrdered(createdFrom, createdTo, "Ngày tạo");
        requireOrdered(ageMin, ageMax, "Tuổi");
        requireOrdered(lastLoginFrom, lastLoginTo, "Lần đăng nhập cuối");
        return this;
    }

    private static void requireAge(Integer age, String label) {
        if (age != null && (age < 0 || age > MAX_AGE)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FILTER_VALUE_INVALID",
                    label + " phải nằm trong 0–" + MAX_AGE);
        }
    }

    private static <T extends Comparable<? super T>> void requireOrdered(T min, T max, String label) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FILTER_RANGE_INVALID",
                    label + ": giá trị \"từ\" phải nhỏ hơn hoặc bằng giá trị \"đến\"");
        }
    }
}
