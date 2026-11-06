package com.finora.investment.service.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.autoinvest.entity.AutoInvestConfig;
import com.finora.investment.domain.autoinvest.entity.AutoInvestMatch;
import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.dto.request.UpdateAutoInvestRequest;
import com.finora.investment.dto.response.AutoInvestConfigResponse;
import com.finora.investment.dto.response.AutoInvestMatchResponse;
import com.finora.investment.repository.AutoInvestConfigRepository;
import com.finora.investment.repository.AutoInvestMatchRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.AutoInvestService;
import com.finora.investment.service.InvestorEligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cấu hình và lịch sử Auto-Invest của nhà đầu tư đang đăng nhập.
 * <p>
 * Bật Auto-Invest là uỷ quyền đặt lệnh thay mình nên kiểm eKYC và xác nhận đúng bản công bố rủi ro hiện hành
 * ngay lúc lưu; lịch sử khớp trả tối đa {@link #MAX_HISTORY} dòng mới nhất.
 */
@Service
@RequiredArgsConstructor
public class AutoInvestServiceImpl implements AutoInvestService {

    public static final int MAX_HISTORY = 100;

    private final AutoInvestConfigRepository autoInvestConfigRepository;
    private final AutoInvestMatchRepository autoInvestMatchRepository;
    private final MarketListingRepository marketListingRepository;
    private final InvestorEligibility investorEligibility;
    private final com.finora.investment.config.InvestorRiskDisclosureProperties investorRiskDisclosureProperties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AutoInvestConfigResponse myConfig() {
        return autoInvestConfigRepository.findByInvestorId(SecurityUtils.getCurrentUserId())
                .map(this::toResponse)
                .orElseGet(this::defaults);
    }

    @Override
    @Transactional
    public AutoInvestConfigResponse saveMyConfig(UpdateAutoInvestRequest request) {
        // Bật Auto-Invest là uỷ quyền đặt lệnh thay mình: kiểm eKYC tại đây vì worker đặt lệnh
        // không có JWT người dùng để kiểm lại.
        if (Boolean.TRUE.equals(request.enabled())) {
            investorEligibility.requireVerifiedInvestor();
            // Q5: worker đặt lệnh thay nhà đầu tư nên quy tắc đang bật phải gắn với xác nhận đúng bản công bố hiện hành.
            requireRiskAcknowledgement(request.riskAcknowledgementVersion());
        }
        String investorId = SecurityUtils.getCurrentUserId();
        var now = clock.instant();
        AutoInvestConfig config = autoInvestConfigRepository.findByInvestorId(investorId)
                .orElseGet(() -> AutoInvestConfig.create(investorId, now));
        boolean wasEnabled = config.isEnabled();
        config.update(request.enabled(), request.grades(), request.minAnnualRate(),
                request.maxTermMonths(), request.amountPerLoan(), now);
        // Chỉ bật quy tắc đang tắt hoặc xác nhận bản công bố khác mới là lần xác nhận mới (app hiện câu xác nhận đúng hai
        // trường hợp này). Sửa tiêu chí của quy tắc đang bật giữ nguyên thời điểm đã xác nhận để bằng chứng không bị ghi đè.
        if (Boolean.TRUE.equals(request.enabled())
                && (!wasEnabled || !config.acknowledgedFor(investorRiskDisclosureProperties.version()))) {
            config.acknowledgeRisk(investorRiskDisclosureProperties.version(), now);
        }
        return toResponse(autoInvestConfigRepository.save(config));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutoInvestMatchResponse> myMatches(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_HISTORY));
        List<AutoInvestMatch> matches = autoInvestMatchRepository.findByInvestorIdOrderByCreatedAtDescIdDesc(
                SecurityUtils.getCurrentUserId(), PageRequest.of(0, size));
        Map<Long, MarketListing> listings = marketListingRepository.findAllById(
                        matches.stream().map(AutoInvestMatch::getListingId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(MarketListing::getId, Function.identity()));
        return matches.stream().map(match -> {
            MarketListing listing = listings.get(match.getListingId());
            return new AutoInvestMatchResponse(
                    match.getCreatedAt(),
                    match.getListingId(),
                    listing == null ? null : listing.getApplicationNumber(),
                    listing == null ? null : listing.getCreditGrade(),
                    listing == null ? null : plain(listing.getAnnualInterestRate()),
                    match.getOutcome().name(),
                    match.getReason(),
                    plain(match.getAmount()),
                    match.getOrderReference());
        }).toList();
    }

    private AutoInvestConfigResponse toResponse(AutoInvestConfig config) {
        return new AutoInvestConfigResponse(
                config.isEnabled(),
                List.copyOf(config.gradeSet()),
                plain(config.getMinAnnualRate()),
                config.getMaxTermMonths(),
                plain(config.getAmountPerLoan()),
                config.getEnabledAt(),
                config.getRiskAcknowledgedVersion(),
                config.getRiskAcknowledgedAt(),
                investorRiskDisclosureProperties.version(),
                config.isEnabled() && !config.acknowledgedFor(investorRiskDisclosureProperties.version()));
    }

    /** Cùng mã lỗi với lệnh tự đặt (FundingServiceImpl) để client xử lý một kiểu. */
    private void requireRiskAcknowledgement(String version) {
        if (version == null || version.isBlank()) {
            throw com.finora.investment.exception.InvestmentDomainException.invalidInput("RISK_ACKNOWLEDGEMENT_REQUIRED",
                    "Bạn cần xác nhận đã đọc và hiểu công bố rủi ro trước khi bật Auto-Invest");
        }
        if (!investorRiskDisclosureProperties.version().equals(version.trim())) {
            throw com.finora.investment.exception.InvestmentDomainException.conflict("RISK_DISCLOSURE_OUTDATED",
                    "Công bố rủi ro đã được cập nhật, vui lòng đọc lại trước khi bật Auto-Invest");
        }
    }

    /**
     * Mặc định khi chưa từng lưu — không tạo dòng chỉ vì người dùng mở màn hình. Vẫn trả phiên bản công bố hiện hành để
     * client hiển thị đúng bản trước lần bật đầu tiên.
     */
    private AutoInvestConfigResponse defaults() {
        return new AutoInvestConfigResponse(false, List.of("A", "B"), "12", 12, "1000000.00", null,
                null, null, investorRiskDisclosureProperties.version(), false);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
