package com.finora.user.dto.response;

/**
 * Một người dùng có họ tên khớp chuỗi tìm, cho service nội bộ lọc dữ liệu của mình theo tên (Chợ chuyển nhượng của
 * Investment tìm giao dịch theo tên người bán/người mua).
 *
 * <p>Trả cả hai dạng ID vì service khác lưu người dùng theo claim {@code user_id} (= ID hồ sơ) hoặc, với tài khoản cũ,
 * theo Keycloak UUID. Không có họ tên hay dữ liệu định danh nào khác: bên gọi đã biết chuỗi tìm.</p>
 *
 * @param userId         ID hồ sơ ({@code user_profiles.id}) dạng chuỗi
 * @param keycloakUserId Keycloak UUID của tài khoản; {@code null} khi hồ sơ chưa gắn tài khoản
 */
public record UserIdMatchResponse(
        String userId,
        String keycloakUserId
) {
}
