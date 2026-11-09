package com.finora.user.service;

import com.finora.user.domain.model.LoginDeviceContext;
import com.finora.user.dto.response.LoginDeviceResponse;

import java.util.List;
import java.util.UUID;

/**
 * Thiết bị đăng nhập của người dùng (LOGIN-DEVICE-001): ghi mỗi lần đăng nhập thành công, cho người dùng xem và đăng
 * xuất thiết bị khác, cho nhân viên hỗ trợ xem ở trang khách hàng.
 */
public interface LoginDeviceService {

    /**
     * Ghi một lần đăng nhập thành công (đăng nhập, xác thực đăng ký). Không bao giờ ném lỗi: ghi thiết bị hỏng chỉ log
     * cảnh báo, đăng nhập vẫn thành công.
     *
     * @param accessToken access token vừa cấp, dùng lấy claim {@code sid} (phiên Keycloak) để đăng xuất thiết bị sau này
     */
    void recordLogin(Long userProfileId, LoginDeviceContext context, String accessToken);

    /**
     * Thiết bị của chính người gọi, đăng nhập gần nhất trước; IP đã che.
     *
     * @param currentDeviceId mã thiết bị của request đang gọi ({@code X-Device-Id} hoặc suy từ User-Agent)
     * @param currentSessionId claim {@code sid} của token đang dùng, {@code null} khi không có
     */
    List<LoginDeviceResponse> myDevices(UUID keycloakUserId, String currentDeviceId, String currentSessionId);

    /** Thiết bị của một khách hàng cho nhân viên hỗ trợ; IP đầy đủ như nhật ký hoạt động. */
    List<LoginDeviceResponse> devicesOf(Long userProfileId);

    /**
     * Đăng xuất một thiết bị khác của chính người gọi: xóa phiên Keycloak lưu lúc thiết bị đó đăng nhập rồi xóa dòng
     * thiết bị. Thiết bị hiện tại không đăng xuất bằng đường này (dùng "Đăng xuất" thường).
     */
    void signOut(UUID keycloakUserId, Long deviceRowId, String currentDeviceId, String currentSessionId);
}
