package com.finora.investment.service;

import java.util.Set;

/**
 * Tra ID người dùng theo họ tên ở User, cho ô tìm theo tên của Chợ chuyển nhượng (người vay, hai bên mua bán, người
 * nắm giữ phần vốn). Investment không giữ bản sao họ tên nên lọc bằng tập ID này.
 */
public interface PartyNameSearch {

    /** Chuỗi tìm theo tên ngắn hơn thế này khớp gần hết người dùng: User không tra, nơi gọi bỏ lọc theo tên. */
    int MIN_LENGTH = 2;

    /** Chuỗi tìm đủ dài để tra theo tên. */
    static boolean searchable(String query) {
        return query != null && query.trim().length() >= MIN_LENGTH;
    }

    /**
     * ID (cả ID hồ sơ lẫn Keycloak UUID) của những người có họ tên chứa {@code query}.
     *
     * @return tập rỗng khi không ai khớp hoặc chuỗi tìm dưới {@link #MIN_LENGTH} ký tự
     * @throws com.finora.common.exception.BusinessException 503 {@code USER_SEARCH_UNAVAILABLE} khi không đọc được User:
     *                                                       báo lỗi thay vì trả "không có kết quả" sai
     */
    Set<String> idsMatching(String query);
}
