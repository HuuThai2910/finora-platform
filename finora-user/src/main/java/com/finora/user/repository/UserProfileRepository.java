package com.finora.user.repository;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.UserRole;
import com.finora.user.domain.entity.UserProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository truy vấn bảng {@code user_profiles}.
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, Long>, JpaSpecificationExecutor<UserProfile> {

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

    /**
     * Số thành viên của từng vai trò nhân viên.
     *
     * @return danh sách cặp {@code [String mã vai trò, Long]}
     */
    @Query("select p.staffRoleCode, count(p) from UserProfile p "
            + "where p.staffRoleCode is not null group by p.staffRoleCode")
    List<Object[]> countGroupedByStaffRoleCode();

    /** Số tài khoản đang giữ một vai trò nhân viên — chặn xoá vai trò còn người dùng. */
    long countByStaffRoleCode(String staffRoleCode);

    /** Keycloak ID của thành viên một vai trò nhân viên — đăng xuất họ khi vai trò đổi quyền. */
    @Query("select p.keycloakUserId from UserProfile p where p.staffRoleCode = :staffRoleCode")
    List<UUID> findKeycloakUserIdsByStaffRoleCode(@Param("staffRoleCode") String staffRoleCode);

    /** Họ tên theo lô ID hồ sơ cho API nội bộ; chỉ chọn cột cần, không nạp cả hồ sơ. */
    @Query("select new com.finora.user.repository.UserFullNameRow(p.id, p.keycloakUserId, p.fullName) "
            + "from UserProfile p where p.id in :ids")
    List<UserFullNameRow> findFullNamesByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * Người dùng có họ tên chứa chuỗi tìm, không phân biệt dấu và hoa thường (cùng cách so với ô tìm trang Khách hàng),
     * ID nhỏ trước. Gọi qua {@link #findFullNamesByNameContaining} để không phải tự truyền bảng chữ có dấu.
     */
    @Query("select new com.finora.user.repository.UserFullNameRow(p.id, p.keycloakUserId, p.fullName) "
            + "from UserProfile p "
            + "where function('translate', lower(p.fullName), :accented, :plain) like :pattern escape '!' "
            + "order by p.id")
    List<UserFullNameRow> findFullNamesByPlainNameLike(@Param("accented") String accented,
                                                       @Param("plain") String plain,
                                                       @Param("pattern") String pattern,
                                                       Pageable pageable);

    /**
     * Họ tên chứa {@code normalizedName} (đã qua {@link UserProfileSpecifications#normalizeSearchText}), tối đa theo
     * {@code pageable}. Chỉ chọn ba cột như bản tra theo lô, không nạp cột CCCD.
     */
    default List<UserFullNameRow> findFullNamesByNameContaining(String normalizedName, Pageable pageable) {
        return findFullNamesByPlainNameLike(UserProfileSpecifications.VIETNAMESE_ACCENTED,
                UserProfileSpecifications.VIETNAMESE_PLAIN, UserProfileSpecifications.containsPattern(normalizedName),
                pageable);
    }

    /** Như trên cho tài khoản cũ mà service khác lưu bằng Keycloak UUID. */
    @Query("select new com.finora.user.repository.UserFullNameRow(p.id, p.keycloakUserId, p.fullName) "
            + "from UserProfile p where p.keycloakUserId in :keycloakUserIds")
    List<UserFullNameRow> findFullNamesByKeycloakUserIdIn(@Param("keycloakUserIds") Collection<UUID> keycloakUserIds);
}
