package com.finora.loan.controller;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminAssessmentExplanationResponse;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.decision.AdminLoanDecisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/loan-applications")
@RequiredArgsConstructor
@Validated
public class AdminLoanDecisionController {

    private final AdminLoanDecisionService adminLoanDecisionService;

    @GetMapping
    public ResponseEntity<PageResponse<AdminLoanReviewSummaryResponse>> listApplications(
            @RequestParam(required = false) LoanApplicationStatus status,
            @RequestParam(required = false) @Size(max = 64) String borrowerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        // borrowerId là ID hồ sơ người dùng ở finora-user (claim user_id) — dùng cho trang khách hàng và mục
        // "Hồ sơ khác của cùng người vay" ở trang thẩm định.
        return ResponseEntity.ok(adminLoanDecisionService.listApplications(status, borrowerId, page, size));
    }

    @GetMapping("/{applicationNumber}/review")
    public ResponseEntity<AdminLoanReviewDetailResponse> reviewDetail(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber
    ) {
        return ResponseEntity.ok(adminLoanDecisionService.reviewDetail(applicationNumber));
    }

    /**
     * Phần giải thích của lần chấm điểm đã dùng để quyết định hồ sơ — SHAP, vết chấm
     * luật và bản diễn giải cho người đọc.
     *
     * <p>Tách khỏi {@code /review} vì payload lớn và chỉ cần khi admin mở phần phân
     * tích, không phải mỗi lần mở hồ sơ.</p>
     */
    @GetMapping("/{applicationNumber}/explanation")
    public ResponseEntity<AdminAssessmentExplanationResponse> assessmentExplanation(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber
    ) {
        return ResponseEntity.ok(adminLoanDecisionService.assessmentExplanation(applicationNumber));
    }

    @PostMapping("/{applicationNumber}/approve")
    public ResponseEntity<AdminLoanDecisionResponse> approve(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody ApproveLoanApplicationRequest request
    ) {
        return ResponseEntity.ok(adminLoanDecisionService.approve(applicationNumber, idempotencyKey, request));
    }

    @PostMapping("/{applicationNumber}/reject")
    public ResponseEntity<AdminLoanDecisionResponse> reject(
            @PathVariable @NotBlank @Size(max = 30) String applicationNumber,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 150) String idempotencyKey,
            @Valid @RequestBody RejectLoanApplicationRequest request
    ) {
        return ResponseEntity.ok(adminLoanDecisionService.reject(applicationNumber, idempotencyKey, request));
    }
}
