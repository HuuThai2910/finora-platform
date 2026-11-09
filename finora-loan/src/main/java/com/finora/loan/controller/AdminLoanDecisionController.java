package com.finora.loan.controller;

import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.application.enums.LoanPurpose;
import com.finora.loan.dto.decision.request.AdminApplicationFilter;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.dto.decision.request.ApproveLoanApplicationRequest;
import com.finora.loan.dto.decision.request.RejectLoanApplicationRequest;
import com.finora.loan.dto.decision.response.AdminAssessmentExplanationResponse;
import com.finora.loan.dto.decision.response.AdminLoanDecisionResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewDetailResponse;
import com.finora.loan.dto.decision.response.AdminLoanReviewSummaryResponse;
import com.finora.loan.dto.decision.response.ApplicationNumberResponse;
import com.finora.loan.dto.response.PageResponse;
import com.finora.loan.service.application.ApplicationNumberLookupService;
import com.finora.loan.service.decision.AdminLoanDecisionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/loan-applications")
@RequiredArgsConstructor
@Validated
public class AdminLoanDecisionController {

    private final AdminLoanDecisionService adminLoanDecisionService;
    private final ApplicationNumberLookupService applicationNumberLookupService;

    @GetMapping
    public ResponseEntity<PageResponse<AdminLoanReviewSummaryResponse>> listApplications(
            @RequestParam(required = false) LoanApplicationStatus status,
            @RequestParam(required = false) @Size(max = 64) String borrowerId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) ApplicationServicingFlag servicing,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate submittedTo,
            @RequestParam(required = false) BigDecimal amountMin,
            @RequestParam(required = false) BigDecimal amountMax,
            @RequestParam(required = false) Integer termMin,
            @RequestParam(required = false) Integer termMax,
            @RequestParam(required = false) BigDecimal scoreMin,
            @RequestParam(required = false) BigDecimal scoreMax,
            @RequestParam(required = false) @Size(max = 10) List<@Size(max = 8) String> grade,
            @RequestParam(required = false) BigDecimal rateMin,
            @RequestParam(required = false) BigDecimal rateMax,
            @RequestParam(required = false) @Size(max = 40) String productCode,
            @RequestParam(required = false) LoanPurpose purposeCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        // borrowerId là ID hồ sơ người dùng ở finora-user (claim user_id) — dùng cho trang khách hàng và mục
        // "Hồ sơ khác của cùng người vay" ở trang thẩm định.
        // q: ô tìm của web, khớp mã hồ sơ/hợp đồng (mới HS-/HĐ- lẫn cũ LA-/LC-) hoặc đúng ID người vay.
        // servicing: lọc khoản vay đang quá hạn (OVERDUE), chờ duyệt cơ cấu (RESCHEDULE_PENDING) hoặc có sự cố (INCIDENT).
        // Bảng lọc nâng cao: ngày nộp theo giờ Việt Nam (gồm cả hai đầu), điểm/hạng của lần chấm mới nhất, grade lặp
        // được (?grade=A&grade=B). Khoảng sai trả 400 FILTER_RANGE_INVALID / FILTER_VALUE_INVALID.
        AdminApplicationFilter filter = new AdminApplicationFilter(submittedFrom, submittedTo, amountMin, amountMax,
                termMin, termMax, scoreMin, scoreMax, grade, rateMin, rateMax, productCode, purposeCode);
        return ResponseEntity.ok(adminLoanDecisionService.listApplications(
                status, borrowerId, q, servicing, filter, page, size));
    }

    /**
     * Mã tra cứu và mã hiển thị theo ID số (tối đa 100 ID), cho các bảng vận hành chỉ có ID hiện mã người đọc được.
     */
    @GetMapping("/numbers")
    public ResponseEntity<List<ApplicationNumberResponse>> applicationNumbers(
            @RequestParam @Size(max = ApplicationNumberLookupService.MAX_IDS) List<Long> ids
    ) {
        return ResponseEntity.ok(applicationNumberLookupService.referencesOf(ids));
    }

    /**
     * Đổi mã nhân viên có trong tay ({@code HS-…}, {@code HĐ-…}/{@code HD-…}, {@code LA-…}, {@code LC-…}) về hồ sơ
     * tương ứng; web dùng mã tra cứu trả về để mở trang thẩm định.
     */
    @GetMapping("/lookup")
    public ResponseEntity<ApplicationNumberResponse> lookup(@RequestParam @NotBlank @Size(max = 40) String code) {
        return ResponseEntity.ok(applicationNumberLookupService.resolve(code));
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
