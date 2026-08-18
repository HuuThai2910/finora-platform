package com.finora.user.repository;

import com.finora.user.domain.UserProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository truy vấn bảng {@code user_profiles}.
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    /** Tìm hồ sơ theo Keycloak user ID — dùng sau khi xác thực JWT */
    Optional<UserProfile> findByKeycloakUserId(UUID keycloakUserId);

    /** Tìm hồ sơ theo email — dùng cho login và quên mật khẩu */
    Optional<UserProfile> findByEmail(String email);

    /** Kiểm tra email đã tồn tại chưa — dùng khi đăng ký */
    boolean existsByEmail(String email);

    /** Kiểm tra số CCCD (hash) đã được đăng ký chưa — tránh trùng lặp eKYC */
    boolean existsByIdNumberHash(String idNumberHash);

    /** Kiểm tra số điện thoại (hash) đã được đăng ký chưa */
    boolean existsByPhoneHash(String phoneHash);

    /** Phân trang danh sách người dùng, sắp xếp theo thời gian tạo giảm dần */
    Page<UserProfile> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
