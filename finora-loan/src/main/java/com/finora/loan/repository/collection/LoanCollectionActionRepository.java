package com.finora.loan.repository.collection;

import com.finora.loan.domain.collection.entity.LoanCollectionAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoanCollectionActionRepository extends JpaRepository<LoanCollectionAction, Long> {
    Optional<LoanCollectionAction> findByActorIdAndIdempotencyKey(String actorId, String idempotencyKey);

    /** Lịch sử xử lý hồ sơ: ghi nhận thu hồi của các đợt, cũ trước. */
    List<LoanCollectionAction> findByCollectionCaseIdInOrderByCreatedAtAsc(Collection<Long> collectionCaseIds);
    Page<LoanCollectionAction> findByCollectionCaseIdOrderByCreatedAtDesc(Long collectionCaseId, Pageable pageable);
}

