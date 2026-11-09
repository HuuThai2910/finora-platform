package com.finora.blockchain.repository.proof;

import com.finora.blockchain.domain.proof.entity.ProofSubmission;
import com.finora.blockchain.domain.proof.enums.ProofSubmissionStatus;
import com.finora.blockchain.dto.request.ProofSearchFilter;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Điều kiện explorer proof khi có bộ lọc thêm của trang Kiểm toán. Ghép cùng trạng thái và tham chiếu (hồ sơ vay theo
 * {@code loanApplicationId}, tham chiếu khác theo aggregate) trong một câu truy vấn có phân trang.
 */
public final class ProofSubmissionSpecifications {

    private ProofSubmissionSpecifications() {
    }

    /**
     * @param loanApplicationId hồ sơ vay khi tra theo {@code LOAN_APPLICATION}; {@code null} là không lọc
     * @param aggregateType     aggregate khi tra theo tham chiếu khác (cùng {@code aggregateId}); {@code null} là không lọc
     * @param zone              múi giờ nghiệp vụ để đổi khoảng ngày sang mốc thời gian
     */
    public static Specification<ProofSubmission> matching(ProofSubmissionStatus status, Long loanApplicationId,
                                                          String aggregateType, String aggregateId,
                                                          ProofSearchFilter filter, ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (status != null) all.add(cb.equal(root.get("status"), status));
            if (loanApplicationId != null) all.add(cb.equal(root.get("loanApplicationId"), loanApplicationId));
            if (aggregateType != null) {
                all.add(cb.equal(root.get("aggregateType"), aggregateType));
                all.add(cb.equal(root.get("aggregateId"), aggregateId));
            }
            if (filter != null) {
                if (filter.proofType() != null) all.add(cb.equal(root.get("proofType"), filter.proofType()));
                if (filter.provider() != null) all.add(cb.equal(root.get("provider"), filter.provider()));
                if (filter.createdFrom() != null) {
                    all.add(cb.greaterThanOrEqualTo(root.<Instant>get("createdAt"),
                            filter.createdFrom().atStartOfDay(zone).toInstant()));
                }
                if (filter.createdTo() != null) {
                    all.add(cb.lessThan(root.<Instant>get("createdAt"),
                            filter.createdTo().plusDays(1).atStartOfDay(zone).toInstant()));
                }
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }
}
