package com.finora.loan.security;

import com.finora.loan.exception.LoanBusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Xác định người dùng đứng sau request hiện tại từ access token của Keycloak.
 *
 * <p>Định danh lấy từ claim {@code user_id} — ID hồ sơ nội bộ do finora-user cấp và
 * gắn lên Keycloak dưới dạng user attribute. Chọn giá trị này thay vì {@code sub} để
 * Loan Service và User Service cùng gọi một người bằng một con số, không phải đối
 * chiếu qua bảng trung gian.</p>
 *
 * <p>Lớp này thay {@code MockCurrentUserProvider} vốn trả cứng ADMIN-001/BORROWER-001;
 * chữ ký hai phương thức giữ nguyên nên các service gọi tới không phải sửa.</p>
 */
@Component
public class CurrentUserProvider {

    /** Phải khớp {@code claim.name} của protocol mapper "user-id" trong realm finora. */
    private static final String USER_ID_CLAIM = "user_id";

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    /**
     * ID người vay thực hiện request.
     *
     * <p>Không kiểm tra vai trò ở đây: quyền truy cập endpoint đã do
     * {@code SecurityConfig} quyết định, còn quyền trên từng hồ sơ do
     * {@code requireOwner} của domain kiểm tra.</p>
     */
    public String borrowerUserId() {
        return requireUserId();
    }

    /**
     * ID quản trị viên thực hiện request.
     *
     * <p>Kiểm tra lại vai trò ADMIN ngay tại đây thay vì chỉ tin vào cấu hình URL, để
     * một endpoint quản trị mới bị quên khai báo không lặng lẽ ghi nhật ký kiểm toán
     * dưới danh nghĩa người dùng thường.</p>
     */
    public String adminUserId() {
        String userId = requireUserId();
        if (!hasAdminAuthority()) {
            throw LoanBusinessException.forbidden(
                    "ADMIN_ROLE_REQUIRED",
                    "Tài khoản không có quyền quản trị để thực hiện thao tác này");
        }
        return userId;
    }

    private String requireUserId() {
        String userId = jwt().getClaimAsString(USER_ID_CLAIM);
        if (userId == null || userId.isBlank()) {
            // Token hợp lệ nhưng thiếu claim nghĩa là mapper của Keycloak chưa được cấu
            // hình hoặc tài khoản chưa được gắn attribute — lỗi cấu hình, không phải lỗi
            // người dùng, nên thông báo tách khỏi trường hợp hết phiên.
            throw LoanBusinessException.unauthorized(
                    "MISSING_USER_ID_CLAIM",
                    "Phiên đăng nhập thiếu thông tin định danh, vui lòng đăng nhập lại");
        }
        return userId;
    }

    private boolean hasAdminAuthority() {
        return authentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }

    private Jwt jwt() {
        if (authentication().getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw LoanBusinessException.unauthorized(
                "INVALID_TOKEN", "Token xác thực không hợp lệ");
    }

    private Authentication authentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw LoanBusinessException.unauthorized(
                    "UNAUTHENTICATED", "Vui lòng đăng nhập để tiếp tục");
        }
        return authentication;
    }
}
