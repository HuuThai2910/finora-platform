package com.finora.investment.controller;

import com.finora.common.dto.PageResponse;
import com.finora.investment.dto.request.UpdateFundingSettingsRequest;
import com.finora.investment.dto.response.AdminInvestorNoteResponse;
import com.finora.investment.dto.response.FundingSettingsResponse;
import com.finora.investment.dto.response.ListingInvestorResponse;
import com.finora.investment.dto.response.MarketListingResponse;
import com.finora.investment.dto.response.NoteResponse;
import com.finora.investment.dto.response.OrderIncidentResponse;
import com.finora.investment.service.AdminInvestorPortfolioService;
import com.finora.investment.service.FundingSettingsService;
import com.finora.investment.service.MarketListingService;
import com.finora.investment.service.NoteIssuanceService;
import com.finora.investment.service.OrderIncidentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Thao tác quản trị của sàn gọi vốn.
 */
@RestController
@RequestMapping("/api/v1/investments/admin")
@RequiredArgsConstructor
@Validated
public class InvestmentAdminController {

    private final MarketListingService marketListingService;
    private final NoteIssuanceService noteIssuanceService;
    private final FundingSettingsService fundingSettingsService;
    private final OrderIncidentService orderIncidentService;
    private final AdminInvestorPortfolioService adminInvestorPortfolioService;

    /** Note một nhà đầu tư đang giữ (theo ID người dùng ở finora-user) — trang chi tiết khách hàng. */
    @GetMapping("/investors/{investorId}/notes")
    public ResponseEntity<PageResponse<AdminInvestorNoteResponse>> investorNotes(
            @PathVariable @jakarta.validation.constraints.Size(max = 100) String investorId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(adminInvestorPortfolioService.notesOf(investorId, page, size));
    }

    /**
     * Ai đã góp vốn vào khoản vay này.
     *
     * <p>Chỉ endpoint quản trị mới trả {@code investorId}; phía nhà đầu tư dùng
     * {@code /investments/portfolio} và chỉ thấy phần vốn của chính mình.</p>
     */
    @GetMapping("/listings/{listingId}/investors")
    public ResponseEntity<List<ListingInvestorResponse>> investors(@PathVariable Long listingId) {
        return ResponseEntity.ok(marketListingService.investorsOf(listingId));
    }

    /** Tham số gọi vốn đang áp dụng cho khoản vay lên sàn từ giờ trở đi. */
    @GetMapping("/settings")
    public ResponseEntity<FundingSettingsResponse> settings() {
        return ResponseEntity.ok(fundingSettingsService.current());
    }

    /**
     * Đổi mệnh giá Note, mức đầu tư tối thiểu và số ngày gọi vốn.
     *
     * <p>Chỉ áp dụng cho khoản vay lên sàn sau đó. Listing và phần vốn đã tạo giữ nguyên
     * mệnh giá cũ, nên cam kết của nhà đầu tư không bị tính lại.</p>
     */
    @PutMapping("/settings")
    public ResponseEntity<FundingSettingsResponse> updateSettings(
            @Valid @RequestBody UpdateFundingSettingsRequest request
    ) {
        return ResponseEntity.ok(fundingSettingsService.update(request));
    }

    /** Sửa lỗi: khóa phần vốn của khoản vay đã giải ngân (Loan đã phát LoanDisbursed). */
    @PostMapping("/listings/{listingId}/finalize")
    public ResponseEntity<Map<String, Object>> finalizeCommitments(@PathVariable Long listingId) {
        return ResponseEntity.ok(
                Map.of("finalizedCount", noteIssuanceService.finalizeCommitmentsAfterDisbursement(listingId)));
    }

    /** Sửa lỗi: phát hành Note còn thiếu cho khoản vay đã giải ngân. */
    @PostMapping("/listings/{listingId}/activate-notes")
    public ResponseEntity<Map<String, Object>> activateNotes(@PathVariable Long listingId) {
        return ResponseEntity.ok(
                Map.of("issuedNoteCount", noteIssuanceService.activateNotesAfterDisbursement(listingId)));
    }

    @GetMapping("/commitments/{commitmentId}/notes")
    public ResponseEntity<List<NoteResponse>> notes(@PathVariable Long commitmentId) {
        return ResponseEntity.ok(noteIssuanceService.notesOfCommitment(commitmentId));
    }

    /**
     * R3: lệnh góp vốn chưa khớp với ví ở Payment (chờ giữ tiền thử lại nhiều lần, chưa nhả được tiền giữ, hoặc nhả bị dừng
     * vì Payment trả lỗi chắc chắn).
     */
    @GetMapping("/order-incidents")
    public ResponseEntity<PageResponse<OrderIncidentResponse>> orderIncidents(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(orderIncidentService.list(page, size));
    }

    /** R3: hẹn worker thử lại ngay lệnh đang dừng hoặc đang lùi lịch, sau khi vận hành đã xử lý nguyên nhân. */
    @PostMapping("/order-incidents/{orderReference}/retry")
    public ResponseEntity<OrderIncidentResponse> retryOrderIncident(@PathVariable String orderReference) {
        return ResponseEntity.ok(orderIncidentService.retry(orderReference));
    }

    @PostMapping("/listings/close-expired")
    public ResponseEntity<Map<String, Object>> closeExpired() {
        return ResponseEntity.ok(Map.of("closedCount", marketListingService.closeExpiredListings(50)));
    }
}
