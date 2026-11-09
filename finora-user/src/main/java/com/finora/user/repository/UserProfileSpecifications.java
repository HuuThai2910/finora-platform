package com.finora.user.repository;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.UserRole;
import com.finora.user.domain.entity.UserLoginDevice;
import com.finora.user.domain.entity.UserProfile;
import com.finora.user.dto.request.UserListFilter;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Điều kiện lọc danh sách người dùng ở màn quản trị (vai trò, trạng thái xác minh, vai trò nhân viên, chuỗi tìm).
 * <p>
 * Số điện thoại và số CCCD chỉ lưu dạng HMAC + bản mã hoá nên không tìm theo chuỗi con được: service băm trước các
 * dạng viết có thể của chuỗi tìm rồi truyền vào {@code identifierHashes} để so khớp đúng giá trị.
 */
public final class UserProfileSpecifications {

    /** Chữ có dấu tiếng Việt (chữ thường) — so họ tên không phân biệt dấu bằng hàm {@code translate} của SQL. */
    static final String VIETNAMESE_ACCENTED =
            "àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ";

    /** Bản không dấu, cùng độ dài và cùng vị trí với {@link #VIETNAMESE_ACCENTED}. */
    static final String VIETNAMESE_PLAIN = stripAccents(VIETNAMESE_ACCENTED);

    private UserProfileSpecifications() {
    }

    /**
     * Ghép mọi điều kiện; tham số {@code null} là không lọc theo tiêu chí đó.
     *
     * @param nameOrEmail      chuỗi tìm đã chuẩn hoá (chữ thường, không dấu), so chuỗi con với họ tên và email
     * @param identifierHashes HMAC của các dạng viết số điện thoại / CCCD; rỗng là bỏ qua
     */
    public static Specification<UserProfile> matching(UserRole role, EkycStatus ekycStatus, String staffRoleCode,
                                                      String nameOrEmail, Collection<String> identifierHashes) {
        return matching(role, null, ekycStatus, staffRoleCode, nameOrEmail, identifierHashes);
    }

    /**
     * Như {@link #matching(UserRole, EkycStatus, String, String, Collection)}, thêm giới hạn theo nhóm vai trò.
     *
     * @param roleScope vai trò được phép (ví dụ ADMIN + STAFF cho trang Phân quyền); {@code null} hoặc rỗng là không giới hạn
     */
    public static Specification<UserProfile> matching(UserRole role, Collection<UserRole> roleScope,
                                                      EkycStatus ekycStatus, String staffRoleCode,
                                                      String nameOrEmail, Collection<String> identifierHashes) {
        return (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (role != null) {
                filters.add(cb.equal(root.get("role"), role));
            }
            if (roleScope != null && !roleScope.isEmpty()) {
                filters.add(root.get("role").in(roleScope));
            }
            if (ekycStatus != null) {
                filters.add(cb.equal(root.get("ekycStatus"), ekycStatus));
            }
            if (staffRoleCode != null) {
                filters.add(cb.equal(root.get("staffRoleCode"), staffRoleCode));
            }
            if (nameOrEmail != null) {
                String pattern = "%" + escapeLike(nameOrEmail) + "%";
                Expression<String> plainName = cb.function("translate", String.class,
                        cb.lower(root.get("fullName")), cb.literal(VIETNAMESE_ACCENTED), cb.literal(VIETNAMESE_PLAIN));
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("email")), pattern, '!'));
                any.add(cb.like(plainName, pattern, '!'));
                if (identifierHashes != null && !identifierHashes.isEmpty()) {
                    any.add(root.get("phoneHash").in(identifierHashes));
                    any.add(root.get("idNumberHash").in(identifierHashes));
                }
                filters.add(cb.or(any.toArray(Predicate[]::new)));
            }
            return cb.and(filters.toArray(Predicate[]::new));
        };
    }

    /**
     * Điều kiện của bảng lọc nâng cao; ghép với {@link #matching} bằng {@code and}. Bộ lọc rỗng không thêm gì.
     *
     * @param today ngày hiện tại theo giờ Việt Nam — mốc tính tuổi
     * @param zone  múi giờ nghiệp vụ để đổi khoảng ngày sang mốc thời gian (gồm cả ngày "đến")
     */
    public static Specification<UserProfile> withFilter(UserListFilter filter, LocalDate today, ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (filter == null || filter.isEmpty()) {
                return cb.and();
            }
            Expression<Instant> createdAt = root.get("createdAt");
            if (filter.createdFrom() != null) {
                filters.add(cb.greaterThanOrEqualTo(createdAt, startOf(filter.createdFrom(), zone)));
            }
            if (filter.createdTo() != null) {
                filters.add(cb.lessThan(createdAt, startOf(filter.createdTo().plusDays(1), zone)));
            }
            if (filter.locked() != null) {
                // Hồ sơ ghi locked_at khi khóa và xóa khi mở khóa (V8).
                filters.add(filter.locked() ? cb.isNotNull(root.get("lockedAt")) : cb.isNull(root.get("lockedAt")));
            }
            Expression<LocalDate> dateOfBirth = root.get("dateOfBirth");
            if (filter.ageMin() != null) {
                // Đủ ageMin tuổi: sinh vào hoặc trước ngày này của ageMin năm trước.
                filters.add(cb.lessThanOrEqualTo(dateOfBirth, today.minusYears(filter.ageMin())));
            }
            if (filter.ageMax() != null) {
                // Chưa tới ageMax + 1 tuổi: sinh sau ngày này của (ageMax + 1) năm trước.
                filters.add(cb.greaterThan(dateOfBirth, today.minusYears(filter.ageMax() + 1L)));
            }
            if (filter.gender() != null) {
                filters.add(cb.equal(root.get("gender"), filter.gender()));
            }
            if (filter.lastLoginFrom() != null || filter.lastLoginTo() != null) {
                // Lần đăng nhập gần nhất trên mọi thiết bị: MAX(last_login_at) của user_login_devices.
                Subquery<Instant> lastLogin = query.subquery(Instant.class);
                Root<UserLoginDevice> device = lastLogin.from(UserLoginDevice.class);
                lastLogin.select(cb.greatest(device.<Instant>get("lastLoginAt")))
                        .where(cb.equal(device.get("userProfileId"), root.get("id")));
                if (filter.lastLoginFrom() != null) {
                    filters.add(cb.greaterThanOrEqualTo(lastLogin, startOf(filter.lastLoginFrom(), zone)));
                }
                if (filter.lastLoginTo() != null) {
                    filters.add(cb.lessThan(lastLogin, startOf(filter.lastLoginTo().plusDays(1), zone)));
                }
            }
            return cb.and(filters.toArray(Predicate[]::new));
        };
    }

    private static Instant startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    /** Chữ thường, bỏ dấu tiếng Việt và khoảng trắng thừa — chuẩn hoá chuỗi tìm cho khớp với {@code translate}. */
    public static String normalizeSearchText(String raw) {
        String collapsed = raw.trim().replaceAll("[ \\t\\n\\r]+", " ").toLowerCase(Locale.ROOT);
        return stripAccents(collapsed);
    }

    static String stripAccents(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.replace('đ', 'd').replace('Đ', 'D');
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
