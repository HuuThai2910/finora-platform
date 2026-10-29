package com.finora.investment.repository;

import com.finora.investment.domain.transferdocument.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteTransferDocumentRepository extends JpaRepository<NoteTransferDocument, Long> {

    /** Lần khớp đã thanh toán xong nhưng chưa có dòng văn bản; cũ nhất trước. */
    @Query("""
            SELECT t.id FROM BookTrade t
            WHERE t.settlementStatus = 'SETTLED'
              AND NOT EXISTS (SELECT d.id FROM NoteTransferDocument d WHERE d.tradeId = t.id)
            ORDER BY t.id ASC
            """)
    List<Long> findSettledTradeIdsWithoutDocument(Pageable pageable);

    /** Văn bản chờ lập đã tới lượt (kể cả lượt bị bỏ dở đã hết lease). */
    @Query("""
            SELECT d.id FROM NoteTransferDocument d
            WHERE d.status = 'PENDING' AND d.nextAttemptAt <= :now
            ORDER BY d.nextAttemptAt ASC, d.id ASC
            """)
    List<Long> findDueIds(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM NoteTransferDocument d WHERE d.id = :id")
    Optional<NoteTransferDocument> findByIdForUpdate(@Param("id") Long id);

    Optional<NoteTransferDocument> findByTradeReference(String tradeReference);

    /** Trang quản trị không nạp bytes PDF: chỉ lấy cột mô tả qua projection. */
    @Query(value = """
            SELECT new com.finora.investment.repository.NoteTransferDocumentRow(
                d.documentNumber, d.tradeReference, d.loanId, d.listingId, t.sellerId, t.buyerId, t.quantity,
                t.amount, d.status, d.contentHash, d.attempts, d.lastError, t.executedAt, d.issuedAt)
            FROM NoteTransferDocument d, BookTrade t
            WHERE t.id = d.tradeId
            ORDER BY d.createdAt DESC, d.id DESC
            """,
            countQuery = "SELECT COUNT(d) FROM NoteTransferDocument d")
    Page<NoteTransferDocumentRow> findRows(Pageable pageable);

    @Query(value = """
            SELECT new com.finora.investment.repository.NoteTransferDocumentRow(
                d.documentNumber, d.tradeReference, d.loanId, d.listingId, t.sellerId, t.buyerId, t.quantity,
                t.amount, d.status, d.contentHash, d.attempts, d.lastError, t.executedAt, d.issuedAt)
            FROM NoteTransferDocument d, BookTrade t
            WHERE t.id = d.tradeId AND d.status = :status
            ORDER BY d.createdAt DESC, d.id DESC
            """,
            countQuery = "SELECT COUNT(d) FROM NoteTransferDocument d WHERE d.status = :status")
    Page<NoteTransferDocumentRow> findRowsByStatus(
            @Param("status") NoteTransferDocumentStatus status, Pageable pageable);
}
