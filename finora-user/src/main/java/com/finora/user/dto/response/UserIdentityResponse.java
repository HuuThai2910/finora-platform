package com.finora.user.dto.response;

/**
 * Danh tính tối thiểu của một người dùng cho service nội bộ in giấy tờ (giấy xác nhận chuyển nhượng Note của
 * Investment).
 *
 * <p>Chỉ có họ tên và số CCCD đã che: bên nhận in lên giấy tờ, không cần và không được nhận số CCCD đầy đủ, ngày sinh,
 * địa chỉ, số điện thoại hay email (tối thiểu hóa dữ liệu, {@code LEGAL-DATA-01}).</p>
 *
 * @param userId         ID hồ sơ nội bộ, cùng giá trị claim {@code user_id} mà Investment lưu làm investorId/buyer/seller
 * @param fullName       họ tên trong hồ sơ, {@code null} khi người dùng chưa có họ tên
 * @param maskedIdNumber số CCCD chỉ giữ 4 số cuối, các ký tự còn lại thay bằng {@code *} và giữ nguyên độ dài;
 *                       {@code null} khi người dùng chưa có CCCD
 */
public record UserIdentityResponse(
        String userId,
        String fullName,
        String maskedIdNumber
) {
}
