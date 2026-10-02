package com.finora.investment.service;

import com.finora.investment.dto.request.UpdateAutoInvestRequest;
import com.finora.investment.dto.response.AutoInvestConfigResponse;
import com.finora.investment.dto.response.AutoInvestMatchResponse;
import java.util.List;

/**
 * Cấu hình và lịch sử Auto-Invest của nhà đầu tư đang đăng nhập.
 */
public interface AutoInvestService {

    AutoInvestConfigResponse myConfig();

    AutoInvestConfigResponse saveMyConfig(UpdateAutoInvestRequest request);

    List<AutoInvestMatchResponse> myMatches(int limit);
}
