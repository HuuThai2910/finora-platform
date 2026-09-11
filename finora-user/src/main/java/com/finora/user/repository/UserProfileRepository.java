package com.finora.user.repository;

import com.finora.user.domain.EkycStatus;
import com.finora.user.domain.UserProfile;
import com.finora.user.domain.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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

    /** Lọc theo vai trò — dùng cho tab vai trò ở màn quản trị */
    Page<UserProfile> findByRoleOrderByCreatedAtDesc(UserRole role, Pageable pageable);

    /** Lọc theo trạng thái eKYC */
    Page<UserProfile> findByEkycStatusOrderByCreatedAtDesc(EkycStatus ekycStatus, Pageable pageable);

    /** Lọc đồng thời theo vai trò và trạng thái eKYC */
    Page<UserProfile> findByRoleAndEkycStatusOrderByCreatedAtDesc(
            UserRole role, EkycStatus ekycStatus, Pageable pageable);

    /**
     * Đếm số người dùng theo từng vai trò trên toàn hệ thống.
     * <p>
     * Đếm ở DB thay vì đếm trên trang đang tải, vì bộ đếm của tab vai trò phải
     * phản ánh tổng số thật chứ không phải phần đang hiển thị.
     *
     * @return danh sách cặp {@code [UserRole, Long]}
     */
    @Query("select p.role, count(p) from UserProfile p group by p.role")
    List<Object[]> countGroupedByRole();

    /** Đếm số người dùng theo từng trạng thái eKYC trên toàn hệ thống. */
    @Query("select p.ekycStatus, count(p) from UserProfile p group by p.ekycStatus")
    List<Object[]> countGroupedByEkycStatus();
}
