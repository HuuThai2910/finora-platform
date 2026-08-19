package com.finora.investment.service;

import com.finora.common.exception.ResourceNotFoundException;
import com.finora.investment.domain.*;
import com.finora.investment.dto.request.AutoInvestRequest;
import com.finora.investment.dto.request.CreateOrderRequest;
import com.finora.investment.repository.AutoInvestConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Auto-Invest — tự động tạo order khi có listing mới phù hợp.
 * Priority FIFO: config cũ nhất được match trước.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoInvestService {

    private final AutoInvestConfigRepository configRepo;
    private final OrderService orderService;
    private final MatchingEngineService matchingEngine;
    private final FundingService fundingService;

    @Transactional
    public AutoInvestConfig createOrUpdate(AutoInvestRequest req) {
        var existing = configRepo.findByInvestorId(req.investorId());

        AutoInvestConfig config;
        if (existing.isPresent()) {
            config = existing.get();
            config.setGradeFilter(req.gradeFilter());
            config.setMinRate(req.minRate());
            config.setMaxRate(req.maxRate());
            config.setMinTerm(req.minTerm());
            config.setMaxTerm(req.maxTerm());
            config.setAmountPerNote(req.amountPerNote());
            config.setTotalBudget(req.totalBudget());
            config.setRemainingBudget(req.totalBudget());
            config.setIsActive(true);
        } else {
            config = AutoInvestConfig.builder()
                    .investorId(req.investorId())
                    .gradeFilter(req.gradeFilter())
                    .minRate(req.minRate())
                    .maxRate(req.maxRate())
                    .minTerm(req.minTerm())
                    .maxTerm(req.maxTerm())
                    .amountPerNote(req.amountPerNote())
                    .totalBudget(req.totalBudget())
                    .remainingBudget(req.totalBudget())
                    .build();
        }

        return configRepo.save(config);
    }

    @Transactional(readOnly = true)
    public AutoInvestConfig findByInvestor(Long investorId) {
        return configRepo.findByInvestorId(investorId)
                .orElseThrow(() -> new ResourceNotFoundException("AutoInvestConfig", "investorId", investorId));
    }

    @Transactional
    public void toggle(Long investorId) {
        var config = findByInvestor(investorId);
        config.setIsActive(!config.getIsActive());
        configRepo.save(config);
    }

    @Transactional
    public void delete(Long investorId) {
        var config = findByInvestor(investorId);
        configRepo.delete(config);
    }

    /**
     * Xử lý listing mới — quét tất cả auto-invest config phù hợp.
     * Gọi từ scheduler hoặc trực tiếp khi có listing mới.
     */
    @Transactional
    public void onNewListing(LoanListing listing) {
        List<AutoInvestConfig> activeConfigs = configRepo.findByIsActiveTrueOrderByCreatedAtAsc();

        for (AutoInvestConfig config : activeConfigs) {
            if (!listing.isOpen()) break;
            if (!config.matchesListing(listing)) continue;

            // Tạo order tự động
            var orderReq = new CreateOrderRequest(
                    config.getInvestorId(),
                    config.getAmountPerNote(),
                    config.getMinRate(),
                    config.getMaxRate(),
                    config.getGradeFilter());

            InvestmentOrder order = orderService.create(orderReq);
            List<MatchResult> matches = matchingEngine.matchOrder(order);

            if (!matches.isEmpty()) {
                fundingService.processMatches(matches);
                config.deductBudget(config.getAmountPerNote());
                configRepo.save(config);

                log.info("Auto-Invest: investor={} → order={} matched listing={} amount={}",
                        config.getInvestorId(), order.getId(),
                        listing.getId(), config.getAmountPerNote());
            }
        }
    }
}
