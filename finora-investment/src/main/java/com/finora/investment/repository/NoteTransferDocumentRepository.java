package com.finora.investment.repository;

import com.finora.investment.domain.transferdocument.entity.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /** Giấy xác nhận của các lần khớp trên một trang, không nạp bytes PDF. */
    @Query("""
            SELECT new com.finora.investment.repository.TradeDocumentView(
                d.tradeReference, d.documentNumber, d.status, d.issuedAt)
            FROM NoteTransferDocument d
            WHERE d.tradeReference IN :tradeReferences
            """)
    List<TradeDocumentView> findViewsByTradeReferences(@Param("tradeReferences") Collection<String> tradeReferences);

    long countByListingIdAndStatus(Long listingId, NoteTransferDocumentStatus status);

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

    /**
     * Ô tìm của bảng giấy xác nhận: {@code code} (đã viết hoa, dạng {@code %…%}) khớp số giấy, mã lần khớp hoặc mã hồ
     * sơ/hợp đồng của khoản vay. {@code status} {@code null} là mọi trạng thái.
     */
    @Query(value = """
            SELECT new com.finora.investment.repository.NoteTransferDocumentRow(
                d.documentNumber, d.tradeReference, d.loanId, d.listingId, t.sellerId, t.buyerId, t.quantity,
                t.amount, d.status, d.contentHash, d.attempts, d.lastError, t.executedAt, d.issuedAt)
            FROM NoteTransferDocument d, BookTrade t
            WHERE t.id = d.tradeId
              AND (:status IS NULL OR d.status = :status)
              AND (UPPER(d.documentNumber) LIKE :code ESCAPE '!' OR UPPER(d.tradeReference) LIKE :code ESCAPE '!'
                   OR d.listingId IN (
                    SELECT l.id FROM MarketListing l
                    WHERE l.applicationCode LIKE :code ESCAPE '!' OR l.contractCode LIKE :code ESCAPE '!'
                       OR l.applicationNumber LIKE :code ESCAPE '!' OR l.contractNumber LIKE :code ESCAPE '!'))
            ORDER BY d.createdAt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d) FROM NoteTransferDocument d
            WHERE (:status IS NULL OR d.status = :status)
              AND (UPPER(d.documentNumber) LIKE :code ESCAPE '!' OR UPPER(d.tradeReference) LIKE :code ESCAPE '!'
                   OR d.listingId IN (
                    SELECT l.id FROM MarketListing l
                    WHERE l.applicationCode LIKE :code ESCAPE '!' OR l.contractCode LIKE :code ESCAPE '!'
                       OR l.applicationNumber LIKE :code ESCAPE '!' OR l.contractNumber LIKE :code ESCAPE '!'))
            """)
    Page<NoteTransferDocumentRow> searchRows(
            @Param("status") NoteTransferDocumentStatus status,
            @Param("code") String code,
            Pageable pageable);
}
