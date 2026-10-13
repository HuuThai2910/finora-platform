package com.finora.investment.service;

import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.exception.InvestmentDomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Điều kiện để một người dùng được bỏ tiền vào khoản vay, ngoài vai trò INVESTOR đã chặn ở
 * {@code SecurityConfig}.
 *
 * <ul>
 *   <li><b>Đã eKYC:</b> đọc claim {@code ekyc_status} do finora-user ghi lên Keycloak — Investment
 *       không gọi ngược User cho mỗi lệnh. Thiếu claim coi như chưa xác minh (fail-closed).</li>
 *   <li><b>Không tự cấp vốn:</b> người vay không được góp vốn hay mua Note của chính khoản vay của
 *       mình — tự cấp vốn làm sai tín hiệu "nhà đầu tư độc lập đã chấp nhận rủi ro".</li>
 * </ul>
 */
@Component
public class InvestorEligibility {

    static final String EKYC_CLAIM = "ekyc_status";
    static final String VERIFIED = "VERIFIED";

    private final boolean requireEkyc;

    public InvestorEligibility(@Value("${finora.investment.require-ekyc:true}") boolean requireEkyc) {
        this.requireEkyc = requireEkyc;
    }

    /**
     * Người dùng đang gọi API đã eKYC. Chạy trong worker (Auto-Invest) thì không có JWT người dùng:
     * điều kiện đã được kiểm lúc nhà đầu tư bật Auto-Invest nên bỏ qua.
     */
    public void requireVerifiedInvestor() {
        if (!requireEkyc) {
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return;
        }
        if (!VERIFIED.equals(jwt.getClaimAsString(EKYC_CLAIM))) {
            throw InvestmentDomainException.forbidden(
                    "EKYC_REQUIRED",
                    "Bạn cần hoàn tất xác minh danh tính (eKYC) trước khi đầu tư. "
                            + "Nếu vừa xác minh xong, hãy đăng nhập lại.");
        }
    }

    /** Chặn người vay tự góp vốn/mua Note khoản vay của chính mình. */
    public void requireNotBorrower(MarketListing listing, String investorId) {
        if (listing.getBorrowerId() != null && listing.getBorrowerId().equals(investorId)) {
            throw InvestmentDomainException.forbidden(
                    "SELF_FUNDING_FORBIDDEN", "Bạn không thể đầu tư vào khoản vay của chính mình");
        }
    }
}
