package com.finora.blockchain.service.proof;

import com.finora.blockchain.domain.proof.ProofReferenceType;
import com.finora.blockchain.domain.proof.ProofSubmission;
import com.finora.blockchain.domain.proof.ProofSubmissionStatus;
import com.finora.blockchain.dto.response.ProofResponse;
import com.finora.blockchain.mapper.ProofResponseMapper;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
import com.finora.common.dto.PageResponse;
import com.finora.common.exception.BusinessException;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explorer proof cho ADMIN. Mỗi kiểu tra cứu đi một index riêng: theo khoản vay {@code idx_..._loan}, theo aggregate
 * {@code idx_..._aggregate}, DEAD_LETTER {@code idx_..._dead_letter}, danh sách chung theo khóa chính giảm dần.
 */
@Service
@RequiredArgsConstructor
public class ProofQueryService {

    private static final Sort CHRONOLOGICAL = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    private static final Sort RECENTLY_UPDATED = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final ProofSubmissionRepository repository;

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
            result = byReference(parseReferenceType(referenceType), referenceId.trim(), PageRequest.of(page, size, CHRONOLOGICAL));
        } else if (status != null && !status.isBlank()) {
            result = repository.findByStatus(parseStatus(status), PageRequest.of(page, size, RECENTLY_UPDATED));
        } else {
            result = repository.findAll(PageRequest.of(page, size, Sort.by(Sort.Order.desc("id"))));
        }
        return PageResponse.<ProofResponse>builder()
                .content(result.getContent().stream().map(ProofResponseMapper::toResponse).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public ProofResponse find(String proofId) {
        return ProofResponseMapper.toResponse(load(repository, parseProofId(proofId)));
    }

    private Page<ProofSubmission> byReference(ProofReferenceType type, String referenceId, PageRequest page) {
        if (type == ProofReferenceType.LOAN_APPLICATION) {
            long loanApplicationId;
            try {
                loanApplicationId = Long.parseLong(referenceId);
            } catch (NumberFormatException exception) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_REFERENCE_INVALID",
                        "LOAN_APPLICATION cần ID khoản vay dạng số");
            }
            return repository.findByLoanApplicationId(loanApplicationId, page);
        }
        return repository.findByAggregateTypeAndAggregateId(type.aggregateType(), referenceId, page);
    }

    static ProofSubmission load(ProofSubmissionRepository repository, UUID proofId) {
        return repository.findByProofId(proofId).orElseThrow(
                () -> new BusinessException(HttpStatus.NOT_FOUND, "PROOF_NOT_FOUND", "Không tìm thấy proof"));
    }

    static UUID parseProofId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_ID_INVALID", "proofId phải là UUID");
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
