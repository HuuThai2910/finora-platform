package com.finora.investment.repository;

import com.finora.investment.domain.note.InvestmentNote;
import com.finora.common.enums.investment.NoteStatus;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface InvestmentNoteRepository extends JpaRepository<InvestmentNote, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from InvestmentNote n where n.id = :noteId")
    java.util.Optional<InvestmentNote> findByIdForUpdate(@Param("noteId") Long noteId);

    List<InvestmentNote> findByCommitmentId(Long commitmentId);

    boolean existsByCommitmentId(Long commitmentId);

    Page<InvestmentNote> findByInvestorIdOrderByIssuedAtDesc(String investorId, Pageable pageable);

    List<InvestmentNote> findByInvestorIdAndStatus(String investorId, NoteStatus status);

    List<InvestmentNote> findByLoanIdAndStatus(Long loanId, NoteStatus status);

    List<InvestmentNote> findByLoanIdAndStatusIn(Long loanId, List<NoteStatus> statuses);

    boolean existsByListingIdAndStatus(Long listingId, NoteStatus status);

    /**
     * Note của người bán trong một đợt gọi vốn còn bán được: chưa tất toán và chưa nằm trong lệnh
     * bán nào. Note cũ trước để thứ tự chọn ổn định, dễ đối soát.
     */
    @Query("""
            SELECT n FROM InvestmentNote n
            WHERE n.listingId = :listingId AND n.investorId = :investorId
              AND n.status IN ('ACTIVE', 'DEFAULTED')
              AND NOT EXISTS (SELECT 1 FROM NoteLock l WHERE l.noteId = n.id)
            ORDER BY n.id ASC
            """)
    List<InvestmentNote> findFreeNotes(
            @Param("listingId") Long listingId, @Param("investorId") String investorId, Pageable pageable);

    @Query("""
            SELECT COUNT(n) FROM InvestmentNote n
            WHERE n.listingId = :listingId AND n.investorId = :investorId
              AND n.status IN ('ACTIVE', 'DEFAULTED')
              AND NOT EXISTS (SELECT 1 FROM NoteLock l WHERE l.noteId = n.id)
            """)
    long countFreeNotes(@Param("listingId") Long listingId, @Param("investorId") String investorId);

    @Query("""
            SELECT COUNT(n) FROM InvestmentNote n, NoteLock l
            WHERE l.noteId = n.id AND n.listingId = :listingId AND n.investorId = :investorId
            """)
    long countLockedNotes(@Param("listingId") Long listingId, @Param("investorId") String investorId);

    /** Note đang khóa trong một lệnh bán, theo đúng thứ tự đã chọn lúc đặt lệnh. */
    @Query("""
            SELECT n FROM InvestmentNote n, NoteLock l
            WHERE l.noteId = n.id AND l.askOrderId = :askOrderId
            ORDER BY n.id ASC
            """)
    List<InvestmentNote> findLockedByAskOrder(@Param("askOrderId") Long askOrderId, Pageable pageable);

    /**
     * Dư nợ gốc lớn nhất trong các Note còn hiệu lực của một đợt gọi vốn — mức trần để tính tiền
     * giữ cho lệnh mua, vì lệnh mua chưa biết sẽ khớp vào Note nào.
     */
    @Query("""
            SELECT MAX(n.outstandingPrincipal) FROM InvestmentNote n
            WHERE n.listingId = :listingId AND n.status IN ('ACTIVE', 'DEFAULTED')
            """)
    BigDecimal findMaxTradableOutstanding(@Param("listingId") Long listingId);

    /** Các đợt gọi vốn còn Note giao dịch được, mới nhất trước — danh sách sổ lệnh. */
    @Query(value = """
            SELECT n.listingId FROM InvestmentNote n
            WHERE n.status IN ('ACTIVE', 'DEFAULTED')
            GROUP BY n.listingId
            ORDER BY n.listingId DESC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT n.listingId) FROM InvestmentNote n
            WHERE n.status IN ('ACTIVE', 'DEFAULTED')
            """)
    Page<Long> findTradableListingIds(Pageable pageable);

    /** Các đợt gọi vốn trong danh sách có Note nợ xấu, một truy vấn cho cả trang. */
    @Query("""
            SELECT DISTINCT n.listingId FROM InvestmentNote n
            WHERE n.listingId IN :listingIds AND n.status = 'DEFAULTED'
            """)
    List<Long> findDefaultedListingIds(@Param("listingIds") List<Long> listingIds);
}
