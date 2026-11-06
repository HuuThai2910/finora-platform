package com.finora.blockchain.service.proof.impl;

import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.domain.proof.enums.ProofReferenceType;
import com.finora.blockchain.domain.proof.enums.ProofSubmissionStatus;
import com.finora.blockchain.dto.response.ProofResponse;
import com.finora.blockchain.mapper.ProofResponseMapper;
import com.finora.blockchain.repository.proof.ProofSubmissionRepository;
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
    public ProofResponse find(String proofId) {
        return ProofResponseMapper.toResponse(
                ProofQueryService.load(proofSubmissionRepository, ProofQueryService.parseProofId(proofId)));
    }

    private Page<ProofSubmission> findByReference(ProofReferenceType type, String referenceId, PageRequest page) {
        if (type == ProofReferenceType.LOAN_APPLICATION) {
            long loanApplicationId;
            try {
                loanApplicationId = Long.parseLong(referenceId);
            } catch (NumberFormatException exception) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "PROOF_REFERENCE_INVALID",
                        "LOAN_APPLICATION cần ID khoản vay dạng số");
            }
            return proofSubmissionRepository.findByLoanApplicationId(loanApplicationId, page);
        }
        return proofSubmissionRepository.findByAggregateTypeAndAggregateId(type.aggregateType(), referenceId, page);
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
