package com.finora.investment.repository;

import com.finora.investment.domain.listing.entity.MarketListing;
import com.finora.investment.domain.orderbook.entity.BookTrade;
import com.finora.investment.domain.orderbook.enums.SettlementStatus;
import com.finora.investment.domain.transferdocument.entity.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.dto.request.AdminTradeFilter;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Điều kiện bảng lần khớp của Chợ chuyển nhượng quản trị: theo sổ, chặng gộp, ngày khớp, số tiền, mã và hai bên. Một
 * câu truy vấn có phân trang; chặng "đang lập giấy" / "hoàn tất" so bằng câu con trên bảng giấy xác nhận.
 */
public final class BookTradeSpecifications {

    private BookTradeSpecifications() {
    }

    /**
     * @param code     mẫu LIKE chữ hoa (đã escape bằng {@code !}) cho mã lần khớp và mã hồ sơ/hợp đồng; {@code null}
     *                 là không tìm theo mã
     * @param partyIds ID người bán hoặc người mua phải khớp (đã tra theo tên); {@code null} là không lọc theo tên. Tập
     *                 rỗng thì nơi gọi trả trang rỗng, không gọi tới đây.
     * @param zone     múi giờ nghiệp vụ để đổi khoảng ngày sang mốc thời gian (gồm cả ngày "đến")
     */
    public static Specification<BookTrade> matching(AdminTradeFilter filter, String code, Collection<String> partyIds,
                                                    ZoneId zone) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (filter.listingId() != null) {
                all.add(cb.equal(root.get("listingId"), filter.listingId()));
            }
            if (filter.settlement() != null) {
                all.add(cb.equal(root.get("settlementStatus"), filter.settlement()));
            }
            if (filter.progress() != null) {
                Subquery<Long> issued = query.subquery(Long.class);
                Root<NoteTransferDocument> document = issued.from(NoteTransferDocument.class);
                issued.select(document.get("id")).where(
                        cb.equal(document.get("tradeId"), root.get("id")),
                        cb.equal(document.get("status"), NoteTransferDocumentStatus.ISSUED));
                Predicate settled = cb.equal(root.get("settlementStatus"), SettlementStatus.SETTLED);
                all.add(switch (filter.progress()) {
                    case SETTLING -> cb.not(settled);
                    case DOC_PENDING -> cb.and(settled, cb.not(cb.exists(issued)));
                    case DONE -> cb.and(settled, cb.exists(issued));
                });
            }
            if (code != null) {
                Subquery<Long> listings = query.subquery(Long.class);
                Root<MarketListing> listing = listings.from(MarketListing.class);
                listings.select(listing.get("id")).where(cb.or(
                        cb.like(listing.get("applicationCode"), code, '!'),
                        cb.like(listing.get("contractCode"), code, '!'),
                        cb.like(listing.get("applicationNumber"), code, '!'),
                        cb.like(listing.get("contractNumber"), code, '!')));
                all.add(cb.or(
                        cb.like(cb.upper(root.get("tradeReference")), code, '!'),
                        root.get("listingId").in(listings)));
            }
            if (partyIds != null) {
                all.add(cb.or(root.get("sellerId").in(partyIds), root.get("buyerId").in(partyIds)));
            }
            if (filter.executedFrom() != null) {
                all.add(cb.greaterThanOrEqualTo(root.<Instant>get("executedAt"),
                        filter.executedFrom().atStartOfDay(zone).toInstant()));
            }
            if (filter.executedTo() != null) {
                all.add(cb.lessThan(root.<Instant>get("executedAt"),
                        filter.executedTo().plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (filter.amountMin() != null) {
                all.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("amount"), filter.amountMin()));
            }
            if (filter.amountMax() != null) {
                all.add(cb.lessThanOrEqualTo(root.<BigDecimal>get("amount"), filter.amountMax()));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }
}
