package com.finora.loan.repository.collection;

import com.finora.loan.domain.collection.entity.LoanCollectionAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoanCollectionActionRepository extends JpaRepository<LoanCollectionAction, Long> {
    Optional<LoanCollectionAction> findByActorIdAndIdempotencyKey(String actorId, String idempotencyKey);
    Page<LoanCollectionAction> findByCollectionCaseIdOrderByCreatedAtDesc(Long collectionCaseId, Pageable pageable);
}

