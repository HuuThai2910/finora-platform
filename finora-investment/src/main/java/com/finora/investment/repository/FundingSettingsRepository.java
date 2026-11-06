package com.finora.investment.repository;

import com.finora.investment.domain.settings.entity.FundingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundingSettingsRepository extends JpaRepository<FundingSettings, Short> {
}
