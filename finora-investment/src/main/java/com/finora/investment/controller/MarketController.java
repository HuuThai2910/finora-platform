package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.response.FundingProgressResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.service.MarketListingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sàn khoản vay đang gọi vốn — phần đọc, dành cho nhà đầu tư.
 */
@RestController
@RequestMapping("/api/v1/market/listings")
@RequiredArgsConstructor
@Validated
public class MarketController {

    private final MarketListingService listingService;

    /**
     * Danh sách khoản vay trên sàn.
     *
     * <p>Không truyền {@code status} thì chỉ trả khoản đang mở — mặc định phục vụ app
     * nhà đầu tư. Truyền {@code ALL} hoặc một trạng thái cụ thể để màn quản trị thấy
     * được khoản đã gọi đủ vốn đang chờ khóa vốn và phát hành Note.</p>
     */
    @GetMapping
    public PageResponse<MarketListingResponse> search(
            @RequestParam(required = false) @Size(max = 20) String status,
            @RequestParam(required = false) @Size(max = 5) String grade,
            @RequestParam(required = false) BigDecimal minRate,
            @RequestParam(required = false) @Min(1) @Max(60) Integer maxTermMonths,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return listingService.search(status, grade, minRate, maxTermMonths, page, size);
    }

    @GetMapping("/{listingId}")
    public MarketListingResponse detail(@PathVariable Long listingId) {
        return listingService.detail(listingId);
    }

    /** Tiến độ gọi vốn, dùng cho thanh phần trăm trên app và web. */
    @GetMapping("/{listingId}/progress")
    public FundingProgressResponse progress(@PathVariable Long listingId) {
        return listingService.progress(listingId);
    }
}
