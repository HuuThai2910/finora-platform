package com.finora.investment.controller;

import com.finora.investment.dto.request.UpdateFundingSettingsRequest;
import com.finora.investment.dto.response.FundingSettingsResponse;
import com.finora.investment.dto.response.ListingInvestorResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.NoteResponse;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.NoteIssuanceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thao tác quản trị của sàn gọi vốn.
 */
@RestController
@RequestMapping("/api/v1/investments/admin")
@RequiredArgsConstructor
@Validated
public class InvestmentAdminController {

    private final MarketListingService listingService;
    private final NoteIssuanceService noteIssuanceService;
    private final FundingSettingsService settingsService;

    /**
     * Ai đã góp vốn vào khoản vay này.
     *
     * <p>Chỉ endpoint quản trị mới trả {@code investorId}; phía nhà đầu tư dùng
     * {@code /investments/portfolio} và chỉ thấy phần vốn của chính mình.</p>
     */
    @GetMapping("/listings/{listingId}/investors")
    public List<ListingInvestorResponse> investors(@PathVariable Long listingId) {
        return listingService.investorsOf(listingId);
    }

    /** Tham số gọi vốn đang áp dụng cho khoản vay lên sàn từ giờ trở đi. */
    @GetMapping("/settings")
    public FundingSettingsResponse settings() {
        return settingsService.current();
    }

    /**
     * Đổi mệnh giá Note, mức đầu tư tối thiểu và số ngày gọi vốn.
     *
     * <p>Chỉ áp dụng cho khoản vay lên sàn sau đó. Listing và phần vốn đã tạo giữ nguyên
     * mệnh giá cũ, nên cam kết của nhà đầu tư không bị tính lại.</p>
     */
    @PutMapping("/settings")
    public FundingSettingsResponse updateSettings(
            @Valid @RequestBody UpdateFundingSettingsRequest request
    ) {
        return settingsService.update(request);
    }

    /** Khóa toàn bộ phần vốn của khoản vay trước khi giải ngân. */
    @PostMapping("/listings/{listingId}/finalize")
    public Map<String, Object> finalizeCommitments(@PathVariable Long listingId) {
        return Map.of("finalizedCount", noteIssuanceService.finalizeCommitments(listingId));
    }

    /** Xé nhỏ phần vốn đã khóa thành các Note. */
    @PostMapping("/listings/{listingId}/activate-notes")
    public Map<String, Object> activateNotes(@PathVariable Long listingId) {
        return Map.of("issuedNoteCount", noteIssuanceService.activateNotes(listingId));
    }

    @GetMapping("/commitments/{commitmentId}/notes")
    public List<NoteResponse> notes(@PathVariable Long commitmentId) {
        return noteIssuanceService.notesOfCommitment(commitmentId);
    }

    @PostMapping("/listings/close-expired")
    public Map<String, Object> closeExpired() {
        return Map.of("closedCount", listingService.closeExpiredListings(50));
    }
}
