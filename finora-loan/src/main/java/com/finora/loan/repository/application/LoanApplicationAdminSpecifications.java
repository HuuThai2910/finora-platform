package com.finora.loan.repository.application;

import com.finora.loan.domain.application.entity.LoanApplication;
import com.finora.loan.domain.application.enums.LoanApplicationStatus;
import com.finora.loan.domain.collection.entity.LoanCollectionCase;
import com.finora.loan.domain.collection.enums.CollectionCaseStatus;
import com.finora.loan.domain.contract.entity.LoanContract;
import com.finora.loan.domain.disbursement.entity.DisbursementSaga;
import com.finora.loan.domain.disbursement.enums.DisbursementSagaStatus;
import com.finora.loan.domain.restructure.entity.LoanRescheduleRequest;
import com.finora.loan.domain.restructure.enums.LoanRescheduleStatus;
import com.finora.loan.domain.scoring.entity.CreditScoringAssessment;
import com.finora.loan.domain.servicing.entity.FinoraLoan;
import com.finora.loan.domain.servicing.entity.LoanReconciliationIncident;
import com.finora.loan.domain.servicing.entity.LoanServicingProjection;
import com.finora.loan.domain.servicing.enums.ApplicationServicingFlag;
import com.finora.loan.domain.servicing.enums.ReconciliationIncidentStatus;
import com.finora.loan.dto.decision.request.AdminApplicationFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Điều kiện danh sách hồ sơ quản trị khi có bộ lọc nâng cao (bảng lọc trang Quản lý khoản vay).
 * <p>
 * Ghép đủ mọi tiêu chí trong một câu truy vấn (cùng nghĩa với {@code searchAdmin}/{@code searchAdminByServicing} của
 * {@link LoanApplicationRepository}) để phân trang và tổng số đúng ở DB. Điểm và hạng lấy từ lần chấm mới nhất
 * ({@code latestCreditAssessmentId}) bằng subquery, không join từng dòng nên số query của trang không đổi.
 */
public final class LoanApplicationAdminSpecifications {

    private LoanApplicationAdminSpecifications() {
    }

    /**
     * @param code        mẫu LIKE (đã escape bằng {@code !}) cho mã hồ sơ/hợp đồng; {@code null} là không tìm
     * @param borrowerKey chuỗi tìm nguyên văn để khớp đúng ID người vay; chỉ dùng khi {@code code} có giá trị
     * @param zone        múi giờ nghiệp vụ để đổi ngày nộp sang mốc thời gian
     */
    public static Specification<LoanApplication> matching(LoanApplicationStatus status, String borrowerId,
                                                          String code, String borrowerKey,
                                                          ApplicationServicingFlag servicing,
                                                          AdminApplicationFilter filter, ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (borrowerId != null) {
                all.add(cb.equal(root.get("borrowerId"), borrowerId));
            }
            if (code != null) {
                all.add(searchCode(root, query, cb, code, borrowerKey));
            }
            if (servicing != null) {
                all.add(servicingFlag(root, query, cb, servicing));
            }
            addFilter(all, root, query, cb, filter, zone);
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    private static Predicate searchCode(Root<LoanApplication> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                        String code, String borrowerKey) {
        Subquery<Long> contracts = query.subquery(Long.class);
        Root<LoanContract> contract = contracts.from(LoanContract.class);
        contracts.select(contract.get("applicationId")).where(cb.or(
                cb.like(contract.get("contractCode"), code, '!'),
                cb.like(contract.get("contractNumber"), code, '!')));
        List<Predicate> any = new ArrayList<>(List.of(
                cb.like(root.get("applicationCode"), code, '!'),
                cb.like(root.get("applicationNumber"), code, '!'),
                root.get("id").in(contracts)));
        if (borrowerKey != null) {
            any.add(cb.equal(root.get("borrowerId"), borrowerKey));
        }
        return cb.or(any.toArray(Predicate[]::new));
    }

    /** Hồ sơ có khoản vay đang mở việc vận hành tương ứng — cùng nghĩa với {@code searchAdminByServicing}. */
    private static Predicate servicingFlag(Root<LoanApplication> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                           ApplicationServicingFlag servicing) {
        return switch (servicing) {
            case OVERDUE -> root.get("id").in(applicationsOfLoans(query, cb, LoanCollectionCase.class,
                    (item, builder) -> builder.equal(item.get("status"), CollectionCaseStatus.OPEN)));
            case RESCHEDULE_PENDING -> root.get("id").in(applicationsOfLoans(query, cb, LoanRescheduleRequest.class,
                    (item, builder) -> builder.equal(item.get("status"), LoanRescheduleStatus.PENDING_REVIEW)));
            case INCIDENT -> {
                Subquery<Long> sagas = query.subquery(Long.class);
                Root<DisbursementSaga> saga = sagas.from(DisbursementSaga.class);
                sagas.select(saga.get("loanApplicationId"))
                        .where(cb.equal(saga.get("status"), DisbursementSagaStatus.REPAIR_REQUIRED));
                yield cb.or(
                        root.get("id").in(applicationsOfLoans(query, cb, LoanReconciliationIncident.class,
                                (item, builder) -> builder.equal(item.get("status"), ReconciliationIncidentStatus.OPEN))),
                        root.get("id").in(applicationsOfLoans(query, cb, LoanServicingProjection.class,
                                (item, builder) -> builder.isTrue(item.get("stale")))),
                        root.get("id").in(sagas));
            }
        };
    }

    /**
     * ID hồ sơ của các khoản vay có bản ghi {@code entity} thỏa {@code condition}: bản ghi vận hành gắn với khoản vay qua
     * {@code finoraLoanId}, khoản vay gắn với hồ sơ qua {@code loanApplicationId}.
     */
    private static <T> Subquery<Long> applicationsOfLoans(CriteriaQuery<?> query, CriteriaBuilder cb, Class<T> entity,
                                                          BiFunction<Root<T>, CriteriaBuilder, Predicate> condition) {
        Subquery<Long> loanIds = query.subquery(Long.class);
        Root<T> item = loanIds.from(entity);
        loanIds.select(item.get("finoraLoanId")).where(condition.apply(item, cb));

        Subquery<Long> applicationIds = query.subquery(Long.class);
        Root<FinoraLoan> loan = applicationIds.from(FinoraLoan.class);
        applicationIds.select(loan.get("loanApplicationId")).where(loan.get("id").in(loanIds));
        return applicationIds;
    }

    private static void addFilter(List<Predicate> all, Root<LoanApplication> root, CriteriaQuery<?> query,
                                  CriteriaBuilder cb, AdminApplicationFilter filter, ZoneId zone) {
        if (filter == null || filter.isEmpty()) {
            return;
        }
        // Hồ sơ cũ có thể chưa ghi submittedAt: lấy thời điểm tạo thay vào.
        Expression<Instant> submitted = cb.coalesce(root.<Instant>get("submittedAt"), root.<Instant>get("createdAt"));
        if (filter.submittedFrom() != null) {
            all.add(cb.greaterThanOrEqualTo(submitted, startOf(filter.submittedFrom(), zone)));
        }
        if (filter.submittedTo() != null) {
            all.add(cb.lessThan(submitted, startOf(filter.submittedTo().plusDays(1), zone)));
        }
        range(all, cb, root.<BigDecimal>get("requestedAmount"), filter.amountMin(), filter.amountMax());
        range(all, cb, root.<Integer>get("requestedTermMonths"), filter.termMin(), filter.termMax());
        // Lãi cuối chỉ có sau định giá theo hạng; trước đó lấy lãi của sản phẩm lúc nộp, khớp cột lãi trên bảng.
        Expression<BigDecimal> rate = cb.coalesce(root.<BigDecimal>get("finalAnnualInterestRate"),
                root.<BigDecimal>get("annualInterestRateSnapshot"));
        range(all, cb, rate, filter.rateMin(), filter.rateMax());
        if (filter.productCode() != null) {
            all.add(cb.equal(root.get("productCodeSnapshot"), filter.productCode()));
        }
        if (filter.purposeCode() != null) {
            all.add(cb.equal(root.get("purposeCode"), filter.purposeCode()));
        }
        if (filter.scoreMin() != null || filter.scoreMax() != null || !filter.grades().isEmpty()) {
            Subquery<Long> assessments = query.subquery(Long.class);
            Root<CreditScoringAssessment> assessment = assessments.from(CreditScoringAssessment.class);
            List<Predicate> conditions = new ArrayList<>();
            range(conditions, cb, assessment.<BigDecimal>get("evaluationScore"), filter.scoreMin(), filter.scoreMax());
            if (!filter.grades().isEmpty()) {
                conditions.add(assessment.get("creditGrade").in(filter.grades()));
            }
            assessments.select(assessment.get("id")).where(conditions.toArray(Predicate[]::new));
            all.add(root.get("latestCreditAssessmentId").in(assessments));
        }
    }

    private static <T extends Comparable<? super T>> void range(List<Predicate> all, CriteriaBuilder cb,
                                                                Expression<? extends T> value, T min, T max) {
        if (min != null) {
            all.add(cb.greaterThanOrEqualTo(value, min));
        }
        if (max != null) {
            all.add(cb.lessThanOrEqualTo(value, max));
        }
    }

    private static Instant startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }
}
