package com.finora.investment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.exception.InvestmentDomainException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class InvestorEligibilityTest {

    private final InvestorEligibility eligibility = new InvestorEligibility(true);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tokenChuaCoClaimEkycThiBiChan() {
        signIn(Map.of("user_id", "7"));

        assertThatThrownBy(eligibility::requireVerifiedInvestor)
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("EKYC_REQUIRED");
    }

    @Test
    void ekycPendingThiBiChanVerifiedThiQua() {
        signIn(Map.of("user_id", "7", "ekyc_status", "PENDING"));
        assertThatThrownBy(eligibility::requireVerifiedInvestor).isInstanceOf(InvestmentDomainException.class);

        signIn(Map.of("user_id", "7", "ekyc_status", "VERIFIED"));
        assertThatCode(eligibility::requireVerifiedInvestor).doesNotThrowAnyException();
    }

    @Test
    void workerKhongCoJwtThiKhongKiem() {
        assertThatCode(eligibility::requireVerifiedInvestor).doesNotThrowAnyException();
    }

    @Test
    void tatCoKiemEkycThiLuonQua() {
        signIn(Map.of("user_id", "7"));
        assertThatCode(new InvestorEligibility(false)::requireVerifiedInvestor).doesNotThrowAnyException();
    }

    @Test
    void nguoiVayKhongDuocTuGopVonKhoanCuaMinh() {
        MarketListing listing = MarketListing.builder().borrowerId("7").build();

        assertThatThrownBy(() -> eligibility.requireNotBorrower(listing, "7"))
                .isInstanceOf(InvestmentDomainException.class)
                .extracting("code").isEqualTo("SELF_FUNDING_FORBIDDEN");
        assertThatCode(() -> eligibility.requireNotBorrower(listing, "8")).doesNotThrowAnyException();
        // Listing tạo từ event cũ chưa có borrowerId: không chặn nhầm ai.
        assertThatCode(() -> eligibility.requireNotBorrower(MarketListing.builder().build(), "7"))
                .doesNotThrowAnyException();
    }

    private void signIn(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claims(c -> c.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
