package com.finora.investment.controller;

import com.finora.investment.dto.request.UpdateFundingSettingsRequest;
import com.finora.investment.dto.response.FundingSettingsResponse;
import com.finora.investment.dto.response.ListingInvestorResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.NoteResponse;
import com.finora.investment.dto.response.OrderIncidentResponse;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.NoteIssuanceService;
import com.finora.investment.service.impl.OrderIncidentService;
import com.finora.common.dto.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final OrderIncidentService orderIncidentService;

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

    /** Sửa lỗi: khóa phần vốn của khoản vay đã giải ngân (Loan đã phát LoanDisbursed). */
    @PostMapping("/listings/{listingId}/finalize")
    public Map<String, Object> finalizeCommitments(@PathVariable Long listingId) {
        return Map.of("finalizedCount", noteIssuanceService.finalizeCommitmentsAfterDisbursement(listingId));
    }

    /** Sửa lỗi: phát hành Note còn thiếu cho khoản vay đã giải ngân. */
    @PostMapping("/listings/{listingId}/activate-notes")
    public Map<String, Object> activateNotes(@PathVariable Long listingId) {
        return Map.of("issuedNoteCount", noteIssuanceService.activateNotesAfterDisbursement(listingId));
    }

    @GetMapping("/commitments/{commitmentId}/notes")
    public List<NoteResponse> notes(@PathVariable Long commitmentId) {
        return noteIssuanceService.notesOfCommitment(commitmentId);
    }

    /**
     * R3: lệnh góp vốn chưa khớp với ví ở Payment (chờ giữ tiền thử lại nhiều lần, chưa nhả được tiền giữ, hoặc nhả bị dừng
     * vì Payment trả lỗi chắc chắn).
     */
    @GetMapping("/order-incidents")
    public PageResponse<OrderIncidentResponse> orderIncidents(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderIncidentService.list(page, size);
    }

    /** R3: hẹn worker thử lại ngay lệnh đang dừng hoặc đang lùi lịch, sau khi vận hành đã xử lý nguyên nhân. */
    @PostMapping("/order-incidents/{orderReference}/retry")
    public OrderIncidentResponse retryOrderIncident(@PathVariable String orderReference) {
        return orderIncidentService.retry(orderReference);
    }

    @PostMapping("/listings/close-expired")
    public Map<String, Object> closeExpired() {
        return Map.of("closedCount", listingService.closeExpiredListings(50));
    }
}
