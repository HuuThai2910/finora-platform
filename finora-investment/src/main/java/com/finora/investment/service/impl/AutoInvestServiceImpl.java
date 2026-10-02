package com.finora.investment.service.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.autoinvest.AutoInvestConfig;
import com.finora.investment.domain.autoinvest.AutoInvestMatch;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.dto.request.UpdateAutoInvestRequest;
import com.finora.investment.dto.response.AutoInvestConfigResponse;
import com.finora.investment.dto.response.AutoInvestMatchResponse;
import com.finora.investment.repository.AutoInvestConfigRepository;
import com.finora.investment.repository.AutoInvestMatchRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.service.AutoInvestService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AutoInvestServiceImpl implements AutoInvestService {

    public static final int MAX_HISTORY = 100;

    private final AutoInvestConfigRepository configRepository;
    private final AutoInvestMatchRepository matchRepository;
    private final MarketListingRepository listingRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public AutoInvestConfigResponse myConfig() {
        return configRepository.findByInvestorId(SecurityUtils.getCurrentUserId())
                .map(this::toResponse)
                .orElseGet(AutoInvestServiceImpl::defaults);
    }

    @Override
    @Transactional
    public AutoInvestConfigResponse saveMyConfig(UpdateAutoInvestRequest request) {
        String investorId = SecurityUtils.getCurrentUserId();
        var now = clock.instant();
        AutoInvestConfig config = configRepository.findByInvestorId(investorId)
                .orElseGet(() -> AutoInvestConfig.create(investorId, now));
        config.update(request.enabled(), request.grades(), request.minAnnualRate(),
                request.maxTermMonths(), request.amountPerLoan(), now);
        return toResponse(configRepository.save(config));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AutoInvestMatchResponse> myMatches(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_HISTORY));
        List<AutoInvestMatch> matches = matchRepository.findByInvestorIdOrderByCreatedAtDescIdDesc(
                SecurityUtils.getCurrentUserId(), PageRequest.of(0, size));
        Map<Long, MarketListing> listings = listingRepository.findAllById(
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
                config.getEnabledAt());
    }

    /** Mặc định khi chưa từng lưu — không tạo dòng chỉ vì người dùng mở màn hình. */
    private static AutoInvestConfigResponse defaults() {
        return new AutoInvestConfigResponse(false, List.of("A", "B"), "12", 12, "1000000.00", null);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
