package com.finora.loan.config;

import org.springframework.stereotype.Component;

/**
 * Cung cấp hai actor giả lập cố định trong lúc Loan Service chưa tích hợp xác thực chung.
 *
 * <p>Product Service luôn sử dụng admin, còn Application Service luôn sử dụng borrower
 * trong cùng một lần chạy. Provider này không xác thực request và bắt buộc phải được
 * thay bằng actor đọc từ JWT khi triển khai lại LN-002.</p>
 */
@Component
public final class MockCurrentUserProvider {

    private static final String ADMIN_USER_ID = "ADMIN-001";
    private static final String BORROWER_USER_ID = "BORROWER-001";

    /**
     * Actor quản trị tạm thời cho thao tác tạo, cập nhật và đổi trạng thái Loan Product.
     */
    public String adminUserId() {
        return ADMIN_USER_ID;
    }

    /**
     * Actor người vay tạm thời cho toàn bộ vòng đời Loan Application cá nhân.
     */
    public String borrowerUserId() {
        return BORROWER_USER_ID;
    }
}
