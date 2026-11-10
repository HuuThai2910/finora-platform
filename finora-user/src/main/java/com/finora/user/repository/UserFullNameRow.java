package com.finora.user.repository;

import java.util.UUID;

/**
 * Ba cột đủ để trả họ tên theo lô: ID hồ sơ, Keycloak ID (để ghép lại với ID bên gọi gửi lên) và họ tên. Không có cột
 * CCCD nên truy vấn không phải giải mã dữ liệu nhạy cảm chỉ để lấy một dòng chữ.
 */
public record UserFullNameRow(Long id, UUID keycloakUserId, String fullName) {
}
