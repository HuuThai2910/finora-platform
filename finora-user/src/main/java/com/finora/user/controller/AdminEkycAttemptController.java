package com.finora.user.controller;

import com.finora.common.dto.PageResponse;
import com.finora.common.security.SecurityUtils;
import com.finora.user.dto.request.EkycApproveRequest;
import com.finora.user.dto.request.EkycRejectRequest;
import com.finora.user.dto.response.EkycAttemptDetailResponse;
import com.finora.user.dto.response.EkycAttemptSummaryResponse;
import com.finora.user.service.EkycAdminReviewService;
import com.finora.user.service.EkycAdminReviewService.EvidenceDownload;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Duyệt eKYC cho admin (EKYC-002 mục 3.7), trả DTO trực tiếp như {@link AdminUserController}.
 * <p>
 * Đường dẫn literal {@code /api/v1/admin/users/ekyc-attempts} được Spring ưu tiên hơn mẫu {@code /{id}}
 * của {@link AdminUserController} nên hai controller dùng chung tiền tố mà không giẫm nhau.
 * Nhân viên xem bằng {@code ops:ekyc:view}, duyệt/từ chối bằng {@code ops:ekyc:action}; authority cũ
 * {@code user:admin:read_all} / {@code user:admin:lock} vẫn được nhận để không gãy tài khoản và service account cũ.
 */
@RestController
@RequestMapping("/api/v1/admin/users/ekyc-attempts")
@RequiredArgsConstructor
public class AdminEkycAttemptController {

    private final EkycAdminReviewService reviewService;

    /** Danh sách lượt theo trạng thái; mặc định {@code MANUAL_REVIEW}, {@code ALL} để xem mọi trạng thái. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:ekyc:view','ROLE_ADMIN')")
    public ResponseEntity<PageResponse<EkycAttemptSummaryResponse>> listAttempts(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(reviewService.list(status, page, size));
    }

    @GetMapping("/{attemptId}")
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:ekyc:view','ROLE_ADMIN')")
    public ResponseEntity<EkycAttemptDetailResponse> getAttempt(@PathVariable String attemptId) {
        return ResponseEntity.ok(reviewService.detail(attemptId, SecurityUtils.getCurrentKeycloakUserId()));
    }

    /**
     * Stream một file bằng chứng qua finora-user (kho lưu không bao giờ mở cho trình duyệt). Không cho
     * trình duyệt/proxy lưu cache vì là ảnh giấy tờ và khuôn mặt.
     */
    @GetMapping("/{attemptId}/evidence/{evidenceId}")
    @PreAuthorize("hasAnyAuthority('user:admin:read_all','ops:ekyc:view','ROLE_ADMIN')")
    public ResponseEntity<InputStreamResource> downloadEvidence(@PathVariable String attemptId,
                                                                @PathVariable String evidenceId) {
        EvidenceDownload download = reviewService.openEvidence(
                attemptId, evidenceId, SecurityUtils.getCurrentKeycloakUserId());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(download.contentType()));
        if (download.contentLength() >= 0) {
            response.contentLength(download.contentLength());
        }
        return response.body(new InputStreamResource(download.object().content()));
    }

    @PostMapping("/{attemptId}/approve")
    @PreAuthorize("hasAnyAuthority('user:admin:lock','ops:ekyc:action','ROLE_ADMIN')")
    public ResponseEntity<EkycAttemptDetailResponse> approve(@PathVariable String attemptId,
                                                             @Valid @RequestBody(required = false) EkycApproveRequest request) {
        return ResponseEntity.ok(reviewService.approve(attemptId, SecurityUtils.getCurrentKeycloakUserId(),
                request == null ? null : request.note()));
    }

    @PostMapping("/{attemptId}/reject")
    @PreAuthorize("hasAnyAuthority('user:admin:lock','ops:ekyc:action','ROLE_ADMIN')")
    public ResponseEntity<EkycAttemptDetailResponse> reject(@PathVariable String attemptId,
                                                            @Valid @RequestBody EkycRejectRequest request) {
        return ResponseEntity.ok(reviewService.reject(attemptId, SecurityUtils.getCurrentKeycloakUserId(),
                request.note()));
    }
}
