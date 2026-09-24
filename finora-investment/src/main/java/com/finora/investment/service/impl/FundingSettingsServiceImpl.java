package com.finora.investment.service.impl;

import com.finora.common.security.SecurityUtils;
import com.finora.investment.domain.settings.FundingSettings;
import com.finora.investment.dto.request.UpdateFundingSettingsRequest;
import com.finora.investment.dto.response.FundingSettingsResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.FundingSettingsRepository;
import com.finora.investment.service.FundingSettingsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FundingSettingsServiceImpl implements FundingSettingsService {

    public static final BigDecimal DEFAULT_NOTE_DENOMINATION = new BigDecimal("1000000.00");
    public static final BigDecimal DEFAULT_MIN_INVESTMENT = new BigDecimal("1000000.00");
    public static final int DEFAULT_FUNDING_DAYS = 14;

    private final FundingSettingsRepository repository;

    @Override
    @Transactional(readOnly = true)
    public FundingSettingsResponse current() {
        return toResponse(load());
    }

    @Override
    @Transactional
    public FundingSettingsResponse update(UpdateFundingSettingsRequest request) {
        SecurityUtils.requireAdmin();

        BigDecimal denomination = positive(request.noteDenomination(), "mệnh giá Note");
        BigDecimal minimum = positive(request.minInvestmentAmount(), "mức đầu tư tối thiểu");

        if (minimum.remainder(denomination).signum() != 0) {
            throw InvestmentDomainException.invalidInput(
                    "MIN_INVESTMENT_NOT_DIVISIBLE",
                    "Mức đầu tư tối thiểu phải chia hết cho mệnh giá Note");
        }
        if (request.fundingDays() == null || request.fundingDays() < 1 || request.fundingDays() > 90) {
            throw InvestmentDomainException.invalidInput(
                    "INVALID_FUNDING_DAYS",
                    "Số ngày gọi vốn phải từ 1 đến 90");
        }

        FundingSettings settings = load();
        settings.setNoteDenomination(denomination);
        settings.setMinInvestmentAmount(minimum);
        settings.setFundingDays(request.fundingDays().shortValue());
        settings.setUpdatedBy(SecurityUtils.getCurrentUserId());
        settings.setUpdatedAt(Instant.now());

        log.info("Đổi tham số gọi vốn: mệnh giá={}, tối thiểu={}, số ngày={}",
                settings.getNoteDenomination(), settings.getMinInvestmentAmount(),
                settings.getFundingDays());
        return toResponse(settings);
    }

    private BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) {
            throw InvestmentDomainException.invalidInput(
                    "INVALID_AMOUNT", label + " phải lớn hơn 0");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal currentNoteDenomination() {
        FundingSettings settings = repository.findById(FundingSettings.SINGLETON_ID).orElse(null);
        return settings != null ? settings.getNoteDenomination() : DEFAULT_NOTE_DENOMINATION;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal currentMinInvestment() {
        FundingSettings settings = repository.findById(FundingSettings.SINGLETON_ID).orElse(null);
        return settings != null ? settings.getMinInvestmentAmount() : DEFAULT_MIN_INVESTMENT;
    }

    @Override
    @Transactional(readOnly = true)
    public int currentFundingDays() {
        FundingSettings settings = repository.findById(FundingSettings.SINGLETON_ID).orElse(null);
        return settings != null
                ? settings.getFundingDays()
                : DEFAULT_FUNDING_DAYS;
    }

    /**
     * Bản ghi cấu hình duy nhất.
     *
     * <p>Migration đã chèn sẵn dòng này. Thiếu nó nghĩa là database bị sửa tay, và tiếp
     * tục với giá trị mặc định trong file sẽ che mất sự cố — nên báo lỗi thẳng.</p>
     */
    private FundingSettings load() {
        return repository.findById(FundingSettings.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Thiếu bản ghi funding_settings id=1; kiểm tra migration V2"));
    }

    private FundingSettingsResponse toResponse(FundingSettings settings) {
        return new FundingSettingsResponse(
                settings.getNoteDenomination().toPlainString(),
                settings.getMinInvestmentAmount().toPlainString(),
                (int) settings.getFundingDays(),
                settings.getUpdatedAt(),
                settings.getUpdatedBy()
        );
    }
}
