package com.finora.investment.controller;

import com.finora.investment.dto.request.UpdateAutoInvestRequest;
import com.finora.investment.dto.response.AutoInvestConfigResponse;
import com.finora.investment.dto.response.AutoInvestMatchResponse;
import com.finora.investment.service.AutoInvestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auto-Invest (C2.1) của nhà đầu tư đang đăng nhập. Việc khớp lệnh do worker làm, không có
 * endpoint kích hoạt thủ công.
 */
@RestController
@RequestMapping("/api/v1/investments/auto-invest")
@RequiredArgsConstructor
@Validated
public class AutoInvestController {

    private final AutoInvestService autoInvestService;

    @GetMapping
    public AutoInvestConfigResponse myConfig() {
        return autoInvestService.myConfig();
    }

    @PutMapping
    public AutoInvestConfigResponse saveMyConfig(@Valid @RequestBody UpdateAutoInvestRequest request) {
        return autoInvestService.saveMyConfig(request);
    }

    @GetMapping("/matches")
    public List<AutoInvestMatchResponse> myMatches(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return autoInvestService.myMatches(limit);
    }
}
