package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.common.security.ops.OpsFunction;
import com.finora.investment.dto.response.FundingProgressResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.service.MarketListingService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RestController;

import com.finora.investment.dto.request.ListingAdvancedFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sàn khoản vay đang gọi vốn — phần đọc, dành cho nhà đầu tư.
 */
@RestController
@RequestMapping("/api/v1/market/listings")
@RequiredArgsConstructor
@Validated
public class MarketController {

    private final MarketListingService marketListingService;

    /**
     * Danh sách khoản vay trên sàn.
     *
     * <p>Không truyền {@code status} thì chỉ trả khoản đang mở — mặc định phục vụ app
     * nhà đầu tư. Truyền {@code ALL} hoặc một trạng thái cụ thể để màn quản trị thấy
     * được khoản đã gọi đủ vốn đang chờ khóa vốn và phát hành Note.</p>
     */
    @GetMapping
    public ResponseEntity<PageResponse<MarketListingResponse>> search(
            @RequestParam(required = false) @Size(max = 20) String status,
            @RequestParam(required = false) @Size(max = 5) String grade,
            @RequestParam(required = false) BigDecimal minRate,
            @RequestParam(required = false) @Min(1) @Max(60) Integer maxTermMonths,
            @RequestParam(required = false) @Size(max = 50) String q,
            @RequestParam(required = false) @Size(max = 20) String sort,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) BigDecimal fundedPercentMin,
            @RequestParam(required = false) BigDecimal fundedPercentMax,
            @RequestParam(required = false) BigDecimal rateMin,
            @RequestParam(required = false) BigDecimal rateMax,
            @RequestParam(required = false) Integer termMin,
            @RequestParam(required = false) Integer termMax,
            @RequestParam(required = false) @Size(max = 10) List<@Size(max = 5) String> grades,
            @RequestParam(required = false) Integer scoreMin,
            @RequestParam(required = false) Integer scoreMax,
            @RequestParam(required = false) @Size(max = 40) String purposeCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closesFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate closesTo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate openedTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        // Khoản vay của chính người xem không hiện trong danh sách đầu tư; quản trị viên và nhân viên có quyền
        // Xem Gọi vốn thấy hết.
        String hiddenBorrowerId = SecurityUtils.hasPermission(OpsFunction.FUNDING.view())
                ? null : SecurityUtils.getCurrentUserId();
        // Bảng lọc nâng cao của web quản trị; tham số cũ (grade, minRate, maxTermMonths) của app giữ nguyên nghĩa.
        // grades là bản nhiều hạng (?grades=A,B); ngày theo giờ Việt Nam, gồm cả hai đầu.
        ListingAdvancedFilter filter = new ListingAdvancedFilter(amountMin, amountMax, fundedPercentMin,
                fundedPercentMax, rateMin, rateMax, termMin, termMax, grades, scoreMin, scoreMax, purposeCode,
                closesFrom, closesTo, openedFrom, openedTo);
        return ResponseEntity.ok(
                marketListingService.search(status, grade, minRate, maxTermMonths, hiddenBorrowerId, q, sort, filter,
                        page, size));
    }

    @GetMapping("/{listingId}")
    public ResponseEntity<MarketListingResponse> detail(@PathVariable Long listingId) {
        return ResponseEntity.ok(marketListingService.detail(listingId));
    }

    /** Tiến độ gọi vốn, dùng cho thanh phần trăm trên app và web. */
    @GetMapping("/{listingId}/progress")
    public ResponseEntity<FundingProgressResponse> progress(@PathVariable Long listingId) {
        return ResponseEntity.ok(marketListingService.progress(listingId));
    }
}
