package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.domain.proof.enums.ProofReferenceType;
import com.finora.blockchain.domain.proof.enums.ProofSubmissionStatus;
import com.finora.blockchain.dto.request.ProofSearchFilter;
import com.finora.blockchain.dto.response.ProofResponse;
import com.finora.blockchain.mapper.ProofResponseMapper;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.blockchain.repository.proof.ProofSubmissionSpecifications;
import com.finora.blockchain.service.proof.ProofQueryService;
import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;

/**
 * Explorer proof cho ADMIN. Mỗi kiểu tra cứu đi một index riêng: theo khoản vay {@code idx_..._loan}, theo aggregate
 * {@code idx_..._aggregate}, DEAD_LETTER {@code idx_..._dead_letter}, danh sách chung theo khóa chính giảm dần.
 */
@Service
@RequiredArgsConstructor
public class ProofQueryServiceImpl implements ProofQueryService {

    private static final Sort CHRONOLOGICAL = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    /** Múi giờ nghiệp vụ: khoảng ngày của bảng lọc tính theo giờ Việt Nam. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Sort RECENTLY_UPDATED = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final ProofSubmissionRepository proofSubmissionRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProofResponse> search(String referenceType, String referenceId, String status, int page, int size) {
        boolean hasType = referenceType != null && !referenceType.isBlank();
        boolean hasId = referenceId != null && !referenceId.isBlank();
        if (hasType != hasId) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_REFERENCE_INCOMPLETE",
                    "Cần cả referenceType và referenceId");
        }
        Page<ProofSubmission> result;
        if (hasType) {
            if (status != null && !status.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_FILTER_UNSUPPORTED",
                        "Lọc theo trạng thái chỉ dùng khi không tra theo tham chiếu");
            }
            result = findByReference(parseReferenceType(referenceType), referenceId.trim(),
                    PageRequest.of(page, size, CHRONOLOGICAL));
        } else if (status != null && !status.isBlank()) {
            result = proofSubmissionRepository.findByStatus(parseStatus(status),
                    PageRequest.of(page, size, RECENTLY_UPDATED));
        } else {
            result = proofSubmissionRepository.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("id"))));
        }
        return toPage(result);
    }

    private static PageResponse<ProofResponse> toPage(Page<ProofSubmission> result) {
        return PageResponse.<ProofResponse>builder()
                .content(result.getContent().stream().map(ProofResponseMapper::toResponse).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProofResponse> search(String referenceType, String referenceId, String status,
                                              ProofSearchFilter filter, int page, int size) {
        boolean hasType = referenceType != null && !referenceType.isBlank();
        boolean hasId = referenceId != null && !referenceId.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        // Không lọc thêm và không ghép trạng thái với tham chiếu: đi đúng đường tra cứu cũ (index riêng từng kiểu).
        if ((filter == null || filter.isEmpty()) && !(hasType && hasStatus)) {
            return search(referenceType, referenceId, status, page, size);
        }
        filter = filter == null ? ProofSearchFilter.NONE : filter.validate();
        if (hasType != hasId) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_REFERENCE_INCOMPLETE",
                    "Cần cả referenceType và referenceId");
        }
        Long loanApplicationId = null;
        String aggregateType = null;
        String aggregateId = null;
        if (hasType) {
            ProofReferenceType type = parseReferenceType(referenceType);
            if (type == ProofReferenceType.LOAN_APPLICATION) {
                loanApplicationId = parseLoanApplicationId(referenceId.trim());
            } else {
                aggregateType = type.aggregateType();
                aggregateId = referenceId.trim();
            }
        }
        ProofSubmissionStatus statusFilter = hasStatus ? parseStatus(status) : null;
        // Tra theo tham chiếu thì cũ nhất trước như explorer; danh sách chung thì mới nhất trước.
        Sort sort = hasType ? CHRONOLOGICAL : Sort.by(Sort.Order.desc("id"));
        Page<ProofSubmission> result = proofSubmissionRepository.findAll(ProofSubmissionSpecifications.matching(
                statusFilter, loanApplicationId, aggregateType, aggregateId, filter, BUSINESS_ZONE),
                PageRequest.of(page, size, sort));
        return toPage(result);
    }

    @Override
    @Transactional(readOnly = true)
    public ProofResponse find(String proofId) {
        return ProofResponseMapper.toResponse(
                ProofQueryService.load(proofSubmissionRepository, ProofQueryService.parseProofId(proofId)));
    }

    private Page<ProofSubmission> findByReference(ProofReferenceType type, String referenceId, PageRequest page) {
        if (type == ProofReferenceType.LOAN_APPLICATION) {
            return proofSubmissionRepository.findByLoanApplicationId(parseLoanApplicationId(referenceId), page);
        }
        return proofSubmissionRepository.findByAggregateTypeAndAggregateId(type.aggregateType(), referenceId, page);
    }

    private static long parseLoanApplicationId(String referenceId) {
        try {
            return Long.parseLong(referenceId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_REFERENCE_INVALID",
                    "LOAN_APPLICATION cần ID khoản vay dạng số");
        }
    }

    private static ProofReferenceType parseReferenceType(String value) {
        return ProofReferenceType.parse(value).orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST,
                "PROOF_REFERENCE_TYPE_INVALID", "referenceType phải là một trong " + Arrays.toString(ProofReferenceType.values())));
    }

    private static ProofSubmissionStatus parseStatus(String value) {
        try {
            return ProofSubmissionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_STATUS_INVALID",
                    "status phải là một trong " + Arrays.toString(ProofSubmissionStatus.values()));
        }
    }
}
