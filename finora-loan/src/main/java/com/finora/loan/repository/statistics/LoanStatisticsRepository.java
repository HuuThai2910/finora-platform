package com.finora.loan.repository.statistics;

import com.finora.common.statistics.StatisticsPeriod;
import com.finora.loan.domain.application.LoanApplicationStatus;
import com.finora.loan.domain.application.LoanFundingStatus;
import com.finora.loan.domain.application.TermsConfirmationStatus;
import com.finora.loan.domain.scoring.CreditAssessmentStatus;
import com.finora.loan.domain.servicing.FinoraLoanStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Câu đếm/tổng hợp cho API thống kê quản trị Loan.
 *
 * <p>Mỗi chỉ số là một câu {@code GROUP BY} trả vài chục dòng tổng hợp, không nạp entity và không lặp
 * truy vấn theo cột hay theo sản phẩm. Dùng SQL thuần vì một chỉ số trải qua nhiều bảng và JPQL không có
 * {@code date_trunc ... AT TIME ZONE}.</p>
 *
 * <p>Cột thời gian được lọc nguyên dạng ({@code col >= :start AND col < :end}) để còn dùng được index nếu
 * sau này thêm; chỉ biểu thức cắt cột mới đổi sang giờ Việt Nam. Hiện chưa có index thời gian (xem
 * STATS-001 §2.3): quy mô vài trăm dòng nên quét tuần tự rẻ hơn duy trì index trên Neon dùng chung.</p>
 *
 * <p>Giá trị trạng thái truyền bằng tham số lấy từ enum, để đổi tên enum thì câu SQL không lặng lẽ đếm 0.</p>
 */
@Repository
@RequiredArgsConstructor
public class LoanStatisticsRepository {

    /** Múi giờ cắt cột; phải trùng {@link StatisticsPeriod#VIETNAM} để cột SQL khớp cột Java khi trải. */
    private static final String VIETNAM_ZONE_SQL = "'Asia/Ho_Chi_Minh'";

    private final NamedParameterJdbcTemplate jdbc;

    // ---------- Ảnh chụp hiện tại ----------

    public List<ApplicationStatusRow> countApplicationsByStatusAndFunding() {
        return jdbc.query("""
                SELECT status, funding_status, COUNT(*) AS total
                FROM loan_applications
                GROUP BY status, funding_status
                """, new MapSqlParameterSource(), (rs, i) -> new ApplicationStatusRow(
                rs.getString("status"), rs.getString("funding_status"), rs.getLong("total")));
    }

    public List<KeyCountRow> countLoansByStatus() {
        return keyCounts("SELECT status AS value_key, COUNT(*) AS total FROM finora_loans GROUP BY status");
    }

    /**
     * Khoản vay còn dư nợ, gom theo sản phẩm và số ngày quá hạn. Nhóm nợ được tính ở Java bằng
     * {@code DebtGroup} từ DPD để không có công thức phân nhóm thứ hai trong SQL; số dòng tối đa là
     * (số sản phẩm × số giá trị DPD khác nhau), không tăng theo số khoản vay.
     */
    public List<OutstandingByProductDpdRow> sumOutstandingByProductAndDpd() {
        return jdbc.query("""
                SELECT a.loan_product_id,
                       p.days_past_due,
                       COUNT(*) AS loans,
                       COALESCE(SUM(p.principal_outstanding), 0) AS principal_outstanding,
                       COALESCE(SUM(p.total_outstanding), 0) AS total_outstanding,
                       COALESCE(SUM(p.overdue_amount), 0) AS overdue_amount,
                       COUNT(*) FILTER (WHERE p.stale) AS stale_projections
                FROM finora_loans l
                JOIN loan_servicing_projections p ON p.finora_loan_id = l.id
                JOIN loan_applications a ON a.id = l.loan_application_id
                WHERE l.closed_at IS NULL AND l.status <> :settled
                GROUP BY a.loan_product_id, p.days_past_due
                """, outstandingParams(), (rs, i) -> new OutstandingByProductDpdRow(
                rs.getLong("loan_product_id"), rs.getInt("days_past_due"), rs.getLong("loans"),
                rs.getBigDecimal("principal_outstanding"), rs.getBigDecimal("total_outstanding"),
                rs.getBigDecimal("overdue_amount"), rs.getLong("stale_projections")));
    }

    public List<OutstandingByGradeRow> sumOutstandingByCreditGrade() {
        return jdbc.query("""
                SELECT a.pricing_credit_grade AS grade,
                       COUNT(*) AS loans,
                       COALESCE(SUM(p.principal_outstanding), 0) AS principal_outstanding
                FROM finora_loans l
                JOIN loan_servicing_projections p ON p.finora_loan_id = l.id
                JOIN loan_applications a ON a.id = l.loan_application_id
                WHERE l.closed_at IS NULL AND l.status <> :settled
                GROUP BY a.pricing_credit_grade
                """, outstandingParams(), (rs, i) -> new OutstandingByGradeRow(
                rs.getString("grade"), rs.getLong("loans"), rs.getBigDecimal("principal_outstanding")));
    }

    /** Toàn bộ danh mục sản phẩm (vài dòng) kèm số hồ sơ, để sản phẩm chưa có hồ sơ vẫn hiện số 0. */
    public List<ProductApplicationsRow> countApplicationsByProduct() {
        return jdbc.query("""
                SELECT lp.id, lp.code, lp.name, COUNT(a.id) AS applications
                FROM loan_products lp
                LEFT JOIN loan_applications a ON a.loan_product_id = lp.id
                GROUP BY lp.id, lp.code, lp.name
                ORDER BY lp.id
                """, new MapSqlParameterSource(), (rs, i) -> new ProductApplicationsRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getLong("applications")));
    }

    public List<CollectionCaseRow> countCollectionCasesByStageAndStatus() {
        return jdbc.query("""
                SELECT stage, status, COUNT(*) AS total
                FROM loan_collection_cases
                GROUP BY stage, status
                """, new MapSqlParameterSource(), (rs, i) -> new CollectionCaseRow(
                rs.getString("stage"), rs.getString("status"), rs.getLong("total")));
    }

    public List<KeyCountRow> countReschedulesByStatus() {
        return keyCounts("SELECT status AS value_key, COUNT(*) AS total FROM loan_reschedule_requests GROUP BY status");
    }

    public List<KeyCountRow> countReconciliationIncidentsByStatus() {
        return keyCounts("SELECT status AS value_key, COUNT(*) AS total FROM loan_reconciliation_incidents GROUP BY status");
    }

    /**
     * Điểm của lần chấm mới nhất mỗi hồ sơ ({@code latest_credit_assessment_id}) khi lần đó thành công.
     * Retry dùng lại cùng assessment nên đây chính là lần chấm thành công mới nhất. Gom theo phần nguyên
     * của điểm (tối đa 101 giá trị) để Java chia 10 cột, giữ quy tắc cột cuối gồm cả 100 ở một chỗ có test.
     */
    public List<ScoreRow> countLatestSucceededScoresByGradeAndScore() {
        return jdbc.query("""
                SELECT s.credit_grade AS grade,
                       CAST(FLOOR(s.evaluation_score) AS integer) AS score,
                       COUNT(*) AS total
                FROM loan_applications a
                JOIN credit_scoring_assessments s ON s.id = a.latest_credit_assessment_id
                WHERE s.status = :succeeded AND s.evaluation_score IS NOT NULL
                GROUP BY s.credit_grade, CAST(FLOOR(s.evaluation_score) AS integer)
                """, new MapSqlParameterSource("succeeded", CreditAssessmentStatus.SUCCEEDED.name()),
                (rs, i) -> new ScoreRow(rs.getString("grade"), rs.getInt("score"), rs.getLong("total")));
    }

    // ---------- Chuỗi theo thời gian ----------

    /**
     * Hồ sơ nộp theo cột và sản phẩm; tổng mỗi cột cộng ở Java nên một câu phục vụ cả points và productPoints.
     * Số tiền xin vay cộng chung câu với số hồ sơ (cùng điều kiện lọc) để "giá trị hồ sơ nộp" luôn khớp
     * "số hồ sơ nộp" của cùng cột, không lệch vì hai lần đọc khác nhau.
     */
    public List<BucketProductCountRow> countSubmittedByBucketAndProduct(StatisticsPeriod period) {
        return jdbc.query("""
                SELECT CAST(date_trunc(:unit, submitted_at AT TIME ZONE %s) AS date) AS bucket_start,
                       loan_product_id,
                       COUNT(*) AS total,
                       COALESCE(SUM(requested_amount), 0) AS requested_amount
                FROM loan_applications
                WHERE submitted_at >= :start AND submitted_at < :end
                GROUP BY 1, 2
                """.formatted(VIETNAM_ZONE_SQL), periodParams(period), (rs, i) -> new BucketProductCountRow(
                rs.getObject("bucket_start", LocalDate.class), rs.getLong("loan_product_id"), rs.getLong("total"),
                rs.getBigDecimal("requested_amount")));
    }

    /**
     * Quyết định duyệt/từ chối theo thời điểm quyết định với trạng thái hiện tại APPROVED/REJECTED.
     * Hồ sơ bị loại ngay ở bước eligibility không ghi {@code admin_decided_at}/{@code automated_decided_at};
     * REJECTED là trạng thái cuối nên {@code updated_at} chính là lúc bị loại, dùng làm mốc dự phòng để các
     * hồ sơ này không biến mất khỏi biểu đồ.
     */
    public List<DecisionCountRow> countDecisionsByBucket(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("approved", LoanApplicationStatus.APPROVED.name())
                .addValue("rejected", LoanApplicationStatus.REJECTED.name());
        return jdbc.query("""
                SELECT CAST(date_trunc(:unit, decided_at AT TIME ZONE %s) AS date) AS bucket_start,
                       COUNT(*) FILTER (WHERE status = :approved) AS approved,
                       COUNT(*) FILTER (WHERE status = :rejected) AS rejected
                FROM (
                    SELECT status, COALESCE(admin_decided_at, automated_decided_at, updated_at) AS decided_at
                    FROM loan_applications
                    WHERE status IN (:approved, :rejected)
                ) decisions
                WHERE decided_at >= :start AND decided_at < :end
                GROUP BY 1
                """.formatted(VIETNAM_ZONE_SQL), params, (rs, i) -> new DecisionCountRow(
                rs.getObject("bucket_start", LocalDate.class), rs.getLong("approved"), rs.getLong("rejected")));
    }

    public List<BucketProductDisbursementRow> sumDisbursedByBucketAndProduct(StatisticsPeriod period) {
        return jdbc.query("""
                SELECT CAST(date_trunc(:unit, l.disbursed_at AT TIME ZONE %s) AS date) AS bucket_start,
                       a.loan_product_id,
                       COUNT(*) AS loans,
                       COALESCE(SUM(l.principal_amount), 0) AS amount
                FROM finora_loans l
                JOIN loan_applications a ON a.id = l.loan_application_id
                WHERE l.disbursed_at >= :start AND l.disbursed_at < :end
                GROUP BY 1, 2
                """.formatted(VIETNAM_ZONE_SQL), periodParams(period), (rs, i) -> new BucketProductDisbursementRow(
                rs.getObject("bucket_start", LocalDate.class), rs.getLong("loan_product_id"),
                rs.getLong("loans"), rs.getBigDecimal("amount")));
    }

    /**
     * Phễu cohort: hồ sơ nộp trong khoảng, đếm số đã tới từng bước theo trạng thái hiện tại.
     * {@code finora_loans.loan_application_id} là unique nên LEFT JOIN không nhân bản hồ sơ.
     */
    public FunnelRow countFunnel(StatisticsPeriod period) {
        MapSqlParameterSource params = periodParams(period)
                .addValue("succeeded", CreditAssessmentStatus.SUCCEEDED.name())
                .addValue("approved", LoanApplicationStatus.APPROVED.name())
                .addValue("termsAccepted", List.of(
                        TermsConfirmationStatus.ACCEPTED.name(), TermsConfirmationStatus.AUTO_AUTHORIZED.name()))
                .addValue("fullyFunded", LoanFundingStatus.FULLY_FUNDED.name());
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS submitted,
                       COUNT(*) FILTER (WHERE s.status = :succeeded) AS scored,
                       COUNT(*) FILTER (WHERE a.status = :approved) AS approved,
                       COUNT(*) FILTER (WHERE a.terms_confirmation_status IN (:termsAccepted)) AS terms_accepted,
                       COUNT(*) FILTER (WHERE a.funding_requested_at IS NOT NULL
                                           OR a.funding_status IS NOT NULL) AS funding_requested,
                       COUNT(*) FILTER (WHERE a.funding_status = :fullyFunded) AS fully_funded,
                       COUNT(l.id) AS disbursed
                FROM loan_applications a
                LEFT JOIN credit_scoring_assessments s ON s.id = a.latest_credit_assessment_id
                LEFT JOIN finora_loans l ON l.loan_application_id = a.id
                WHERE a.submitted_at >= :start AND a.submitted_at < :end
                """, params, (rs, i) -> new FunnelRow(
                rs.getLong("submitted"), rs.getLong("scored"), rs.getLong("approved"),
                rs.getLong("terms_accepted"), rs.getLong("funding_requested"),
                rs.getLong("fully_funded"), rs.getLong("disbursed")));
    }

    private List<KeyCountRow> keyCounts(String sql) {
        return jdbc.query(sql, new MapSqlParameterSource(),
                (rs, i) -> new KeyCountRow(rs.getString("value_key"), rs.getLong("total")));
    }

    /** "Còn dư nợ" = chưa đóng và chưa tất toán; trạng thái truyền từ enum để không lệch tên. */
    private static MapSqlParameterSource outstandingParams() {
        return new MapSqlParameterSource("settled", FinoraLoanStatus.SETTLED.name());
    }

    /**
     * Mốc lọc truyền dạng {@code OffsetDateTime} UTC (ánh xạ JDBC 4.2 chuẩn của {@code timestamptz}),
     * tránh {@code Timestamp} bị driver hiểu theo múi giờ của JVM.
     */
    private static MapSqlParameterSource periodParams(StatisticsPeriod period) {
        return new MapSqlParameterSource()
                .addValue("unit", period.bucket().sqlUnit())
                .addValue("start", period.startInclusive().atOffset(ZoneOffset.UTC))
                .addValue("end", period.endExclusive().atOffset(ZoneOffset.UTC));
    }

    public record KeyCountRow(String key, long total) {}

    public record ApplicationStatusRow(String status, String fundingStatus, long total) {}

    public record OutstandingByProductDpdRow(long productId, int daysPastDue, long loans,
            BigDecimal principalOutstanding, BigDecimal totalOutstanding, BigDecimal overdueAmount,
            long staleProjections) {}

    public record OutstandingByGradeRow(String grade, long loans, BigDecimal principalOutstanding) {}

    public record ProductApplicationsRow(long productId, String code, String name, long applications) {}

    public record CollectionCaseRow(String stage, String status, long total) {}

    public record ScoreRow(String grade, int score, long total) {}

    /** @param requestedAmount tổng {@code requested_amount} của các hồ sơ được đếm trong {@code total} */
    public record BucketProductCountRow(LocalDate bucketStart, long productId, long total,
            BigDecimal requestedAmount) {}

    public record DecisionCountRow(LocalDate bucketStart, long approved, long rejected) {}

    public record BucketProductDisbursementRow(LocalDate bucketStart, long productId, long loans,
            BigDecimal amount) {}

    public record FunnelRow(long submitted, long scored, long approved, long termsAccepted,
            long fundingRequested, long fullyFunded, long disbursed) {}
}
