package com.finora.investment.repository;

import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.common.enums.investment.ListingStatus;
import com.finora.investment.dto.request.ListingAdvancedFilter;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Điều kiện danh sách sàn khi có bộ lọc nâng cao (bảng lọc trang Gọi vốn). Cùng nghĩa với
 * {@link MarketListingRepository#search} cho các tham số cũ, cộng các khoảng mới; một câu truy vấn có phân trang, không
 * đọc thêm theo từng dòng.
 */
public final class MarketListingSpecifications {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private MarketListingSpecifications() {
    }

    /**
     * @param grade            hạng của tham số cũ {@code grade} (một hạng); {@code null} là không lọc
     * @param q                mẫu LIKE chữ thường (đã escape bằng {@code !}) cho mã tra cứu; {@code null} là không tìm
     * @param code             mẫu LIKE chữ hoa cho mã hiển thị HS-/HĐ-
     * @param zone             múi giờ nghiệp vụ để đổi khoảng ngày sang mốc thời gian (gồm cả ngày "đến")
     */
    public static Specification<MarketListing> matching(ListingStatus status, String grade, BigDecimal minRate,
                                                        Integer maxTermMonths, String hiddenBorrowerId, String q,
                                                        String code, ListingAdvancedFilter filter, ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (status != null) all.add(cb.equal(root.get("status"), status));
            if (grade != null) all.add(cb.equal(root.get("creditGrade"), grade));
            if (minRate != null) all.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("annualInterestRate"), minRate));
            if (maxTermMonths != null) all.add(cb.lessThanOrEqualTo(root.<Integer>get("termMonths"), maxTermMonths));
            if (hiddenBorrowerId != null) {
                all.add(cb.or(cb.isNull(root.get("borrowerId")), cb.notEqual(root.get("borrowerId"), hiddenBorrowerId)));
            }
            if (q != null) {
                all.add(cb.or(
                        cb.like(cb.lower(root.<String>get("applicationNumber")), q, '!'),
                        cb.like(cb.lower(root.<String>get("contractNumber")), q, '!'),
                        cb.like(root.<String>get("applicationCode"), code, '!'),
                        cb.like(root.<String>get("contractCode"), code, '!')));
            }
            if (filter != null && !filter.isEmpty()) {
                addFilter(all, root.<BigDecimal>get("targetAmount"), root.<BigDecimal>get("committedAmount"),
                        root.<BigDecimal>get("annualInterestRate"), root.<Integer>get("termMonths"),
                        root.<Integer>get("creditScore"), cb, filter);
                if (!filter.grades().isEmpty()) all.add(root.get("creditGrade").in(filter.grades()));
                if (filter.purposeCode() != null) all.add(cb.equal(root.get("purposeCode"), filter.purposeCode()));
                dateRange(all, cb, root.<Instant>get("fundingClosesAt"), filter.closesFrom(), filter.closesTo(), zone);
                dateRange(all, cb, root.<Instant>get("fundingOpenedAt"), filter.openedFrom(), filter.openedTo(), zone);
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    private static void addFilter(List<Predicate> all, Expression<BigDecimal> target, Expression<BigDecimal> committed,
                                  Expression<BigDecimal> rate, Expression<Integer> term, Expression<Integer> score,
                                  CriteriaBuilder cb, ListingAdvancedFilter filter) {
        range(all, cb, target, filter.amountMin(), filter.amountMax());
        range(all, cb, rate, filter.rateMin(), filter.rateMax());
        range(all, cb, term, filter.termMin(), filter.termMax());
        range(all, cb, score, filter.scoreMin(), filter.scoreMax());
        // Tiến độ = committed / target × 100; so bằng phép nhân để không chia cho 0 và không lệch do làm tròn.
        Expression<BigDecimal> committedTimesHundred = cb.prod(committed, HUNDRED);
        if (filter.fundedPercentMin() != null) {
            all.add(cb.greaterThanOrEqualTo(committedTimesHundred, cb.prod(target, filter.fundedPercentMin())));
        }
        if (filter.fundedPercentMax() != null) {
            all.add(cb.lessThanOrEqualTo(committedTimesHundred, cb.prod(target, filter.fundedPercentMax())));
        }
    }

    private static <T extends Comparable<? super T>> void range(List<Predicate> all, CriteriaBuilder cb,
                                                                Expression<? extends T> value, T min, T max) {
        if (min != null) all.add(cb.greaterThanOrEqualTo(value, min));
        if (max != null) all.add(cb.lessThanOrEqualTo(value, max));
    }

    private static void dateRange(List<Predicate> all, CriteriaBuilder cb, Expression<Instant> value,
                                  LocalDate from, LocalDate to, ZoneId zone) {
        if (from != null) all.add(cb.greaterThanOrEqualTo(value, from.atStartOfDay(zone).toInstant()));
        if (to != null) all.add(cb.lessThan(value, to.plusDays(1).atStartOfDay(zone).toInstant()));
    }
}
