package com.finora.user.dto.response;

/**
 * Họ tên của một người dùng cho service nội bộ hiện danh sách (danh sách gọi vốn của Investment).
 *
 * <p>Chỉ có họ tên: bản tra theo lô không mang CCCD, kể cả bản đã che, vì danh sách chỉ cần một dòng chữ để đọc
 * ({@code LEGAL-DATA-01}).</p>
 *
 * @param userId   giá trị bên gọi đã gửi (ID hồ sơ hoặc Keycloak UUID), để bên gọi ghép lại đúng dòng
 * @param fullName họ tên trong hồ sơ, {@code null} khi người dùng chưa có họ tên
 */
public record UserFullNameResponse(
        String userId,
        String fullName
) {
}
