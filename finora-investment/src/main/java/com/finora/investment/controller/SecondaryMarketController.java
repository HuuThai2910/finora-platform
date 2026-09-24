package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.request.ListNoteForSaleRequest;
import com.finora.investment.dto.response.NoteListingResponse;
import com.finora.investment.service.SecondaryMarketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chợ thứ cấp Notes: bảng tin, đăng bán, rút tin và mua lại.
 *
 * <p>Khác sàn sơ cấp, bảng tin ở đây <strong>yêu cầu đăng nhập</strong>: tin đăng bán mang mã
 * nhà đầu tư của người bán, nên không phải thông tin niêm yết công khai.</p>
 */
@RestController
@RequestMapping("/api/v1/investments/secondary")
@RequiredArgsConstructor
@Validated
public class SecondaryMarketController {

    private final SecondaryMarketService secondaryMarketService;

    /** Bảng tin: các Note đang được treo bán. */
    @GetMapping("/listings")
    public PageResponse<NoteListingResponse> browse(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return toPageResponse(secondaryMarketService.browse(PageRequest.of(page, size)));
    }

    /** Tin đăng bán của chính người đang đăng nhập. */
    @GetMapping("/my-listings")
    public PageResponse<NoteListingResponse> myListings(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return toPageResponse(secondaryMarketService.myListings(PageRequest.of(page, size)));
    }

    /** Treo một Note mình đang giữ lên bảng tin. */
    @PostMapping("/notes/{noteId}/listings")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteListingResponse listForSale(
            @PathVariable Long noteId,
            @Valid @RequestBody ListNoteForSaleRequest request) {
        return secondaryMarketService.listForSale(noteId, request);
    }

    /**
     * Mua một Note đang treo bán.
     *
     * <p>Không cần {@code Idempotency-Key} từ phía gọi: mã tin đăng bán đã là khóa tự nhiên cho
     * giao dịch này — một tin chỉ bán được một lần — nên service tự suy mã chống trùng lặp từ đó.
     * Khác luồng đặt lệnh sơ cấp, nơi cùng một người có thể đặt nhiều lệnh vào cùng khoản vay và
     * cần khóa do phía gọi cung cấp để phân biệt.</p>
     */
    @PostMapping("/listings/{listingReference}/buy")
    public NoteListingResponse buy(
            @PathVariable @NotBlank @Size(max = 50) String listingReference) {
        return secondaryMarketService.buy(listingReference);
    }

    /** Người bán rút tin của mình khi chưa ai mua. */
    @DeleteMapping("/listings/{listingReference}")
    public NoteListingResponse cancel(
            @PathVariable @NotBlank @Size(max = 50) String listingReference) {
        return secondaryMarketService.cancelListing(listingReference);
    }

    private PageResponse<NoteListingResponse> toPageResponse(Page<NoteListingResponse> page) {
        return PageResponse.<NoteListingResponse>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
}
