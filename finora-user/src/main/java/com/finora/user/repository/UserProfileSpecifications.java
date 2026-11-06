package com.finora.user.repository;

import com.finora.common.enums.user.EkycStatus;
import com.finora.common.enums.user.UserRole;
import com.finora.user.domain.entity.UserProfile;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.text.Normalizer;
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
        return (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (role != null) {
                filters.add(cb.equal(root.get("role"), role));
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
