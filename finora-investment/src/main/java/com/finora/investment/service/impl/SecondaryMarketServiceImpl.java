package com.finora.investment.service.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.common.security.SecurityUtils;
import com.finora.investment.client.PaymentClient;
import com.finora.investment.client.PaymentTransferResult;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.secondary.NoteListing;
import com.finora.investment.domain.secondary.NoteListingStatus;
import com.finora.investment.dto.request.ListNoteForSaleRequest;
import com.finora.investment.dto.response.NoteListingResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteListingRepository;
import com.finora.investment.service.SecondaryMarketService;
import com.finora.investment.service.SecondaryMarketTransactionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chợ thứ cấp Notes (task E1).
 *
 * <p>Nhà đầu tư đang giữ Note nhưng cần tiền trước hạn treo Note lên bảng tin; nhà đầu tư khác
 * mua lại và nhận quyền hưởng gốc lãi còn lại. Người vay không liên quan: họ vẫn trả đúng lịch,
 * chỉ đích đến của tiền đổi sang người mua.</p>
 *
 * <p>Ba quyết định nghiệp vụ, theo plan {@code plans/INV-E1-secondary-market-flow.md}:</p>
 * <ul>
 *   <li><b>Trần giá bằng dư nợ gốc còn lại.</b> Người mua trả tối đa bằng phần gốc sẽ nhận về,
 *       nên luôn có lợi phần lãi tương lai. Không chọn trần "gốc cộng lãi còn lại" vì mức đó
 *       phải tự tính lãi tương lai, tức tự đặt một công thức tài chính chưa được duyệt.</li>
 *   <li><b>Phí 5% trừ của người bán.</b> Người mua trả đúng giá treo. Nếu trừ của người mua thì
 *       tổng họ bỏ ra vượt dư nợ gốc và nguyên tắc trên bị phá.</li>
 *   <li><b>Cho bán Note nợ xấu.</b> Đây là lúc người bán cần thoát nhất; bù lại phản hồi mang
 *       cờ {@code defaulted} để giao diện cảnh báo ở cả màn đăng bán và màn xác nhận mua.</li>
 * </ul>
 *
 * <p>Việc mua chia thành ba bước quanh lời gọi Payment, cùng lý do với luồng đặt lệnh sơ cấp:
 * <strong>không giữ database transaction trong lúc chờ mạng</strong>. Hai transaction thật nằm ở
 * {@link SecondaryMarketTransactionService}; class này giữ phần điều phối không transaction.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecondaryMarketServiceImpl implements SecondaryMarketService {

    /**
     * Phí chuyển nhượng, tính trên giá bán.
     *
     * <p>Là policy nghiệp vụ của nền tảng, <strong>không</strong> phải quy định pháp luật — khác
     * với trần lãi suất và trần dư nợ vốn có căn cứ văn bản.</p>
     */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.05");

    private static final int MONEY_SCALE = 2;

    private final NoteListingRepository listingRepository;
    private final InvestmentNoteRepository noteRepository;
    private final MarketListingRepository marketListingRepository;
    private final SecondaryMarketTransactionService transactions;
    private final PaymentClient paymentClient;

    @Override
    @Transactional
    public NoteListingResponse listForSale(Long noteId, ListNoteForSaleRequest request) {
        String sellerId = SecurityUtils.getCurrentUserId();

        InvestmentNote note = noteRepository.findById(noteId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_NOT_FOUND", "Không tìm thấy Note này"));

        if (!note.getInvestorId().equals(sellerId)) {
            throw InvestmentDomainException.forbidden(
                    "NOT_NOTE_OWNER", "Chỉ chủ sở hữu Note mới đăng bán được");
        }

        if (note.getStatus() == NoteStatus.CLOSED) {
            throw InvestmentDomainException.invalidInput(
                    "NOTE_ALREADY_CLOSED", "Note đã tất toán nên không còn gì để bán");
        }

        listingRepository.findByNoteIdAndStatus(noteId, NoteListingStatus.OPEN)
                .ifPresent(existing -> {
                    throw InvestmentDomainException.conflict(
                            "NOTE_ALREADY_LISTED",
                            "Note này đang được treo bán ở tin " + existing.getListingReference());
                });

        BigDecimal outstanding = note.getOutstandingPrincipal();
        BigDecimal price = request.askingPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        if (price.compareTo(outstanding) > 0) {
            throw InvestmentDomainException.invalidInput(
                    "ASKING_PRICE_ABOVE_OUTSTANDING",
                    "Giá bán không được vượt dư nợ gốc còn lại của Note; nếu vượt thì người mua"
                            + " bỏ ra nhiều hơn phần gốc họ nhận về");
        }

        Instant now = Instant.now();
        NoteListing saved = listingRepository.save(NoteListing.builder()
                .listingReference(newReference())
                .noteId(noteId)
                .sellerId(sellerId)
                .askingPrice(price)
                .outstandingAtListing(outstanding)
                .defaultedAtListing(note.getStatus() == NoteStatus.DEFAULTED)
                .status(NoteListingStatus.OPEN)
                .createdBy(sellerId)
                .updatedBy(sellerId)
                .createdAt(now)
                .updatedAt(now)
                .build());

        log.info("Đăng bán Note trên chợ thứ cấp: listingReference={}, noteId={}",
                saved.getListingReference(), noteId);
        return toResponse(saved, note);
    }

    @Override
    @Transactional
    public NoteListingResponse cancelListing(String listingReference) {
        String sellerId = SecurityUtils.getCurrentUserId();

        NoteListing listing = listingRepository.findByListingReference(listingReference)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_LISTING_NOT_FOUND", "Không tìm thấy tin đăng bán này"));

        if (!listing.getSellerId().equals(sellerId)) {
            throw InvestmentDomainException.forbidden(
                    "NOT_LISTING_OWNER", "Chỉ người đăng bán mới rút được tin này");
        }

        if (listing.getStatus() != NoteListingStatus.OPEN) {
            throw InvestmentDomainException.conflict(
                    "NOTE_LISTING_NOT_OPEN",
                    listing.getStatus() == NoteListingStatus.SOLD
                            ? "Note đã bán nên không rút tin được nữa"
                            : "Tin này đã được rút trước đó");
        }

        Instant now = Instant.now();
        listing.setStatus(NoteListingStatus.CANCELLED);
        listing.setCancelledAt(now);
        listing.setUpdatedBy(sellerId);
        listing.setUpdatedAt(now);
        NoteListing saved = listingRepository.save(listing);

        log.info("Rút tin đăng bán: listingReference={}", listingReference);
        return toResponse(saved, requireNote(saved.getNoteId()));
    }

    /**
     * Mua một Note đang treo bán.
     *
     * <p>Ba bước tách nhau bởi lời gọi Payment:</p>
     * <ol>
     *   <li>Khóa tin và kiểm mọi điều kiện, chưa chạm vào tiền.</li>
     *   <li>Chuyển tiền — gọi Payment ngoài transaction, dùng mã tin làm khóa chống trùng lặp.</li>
     *   <li>Đổi chủ Note, đóng tin, ghi lịch sử.</li>
     * </ol>
     *
     * <p>Nếu bước 3 thất bại sau khi tiền đã chuyển, giao dịch <strong>không</strong> được nhả
     * tiền ngược: chuyển nhượng là dứt điểm, không có bước hoàn như giữ chỗ. Lần gọi lại sẽ nhận
     * ra mã giao dịch đã dùng và ghi nhận tiếp phần còn thiếu.</p>
     */
    @Override
    public NoteListingResponse buy(String listingReference) {
        String buyerId = SecurityUtils.getCurrentUserId();

        NoteListing locked = transactions.lockAndValidateForPurchase(listingReference, buyerId);

        BigDecimal price = locked.getAskingPrice();
        BigDecimal fee = platformFee(price);

        // Mã giao dịch suy ra từ mã tin nên cố định: gọi lại sau lỗi mạng dùng đúng mã cũ và
        // Payment chỉ chuyển tiền một lần.
        String paymentReference = "NTRF-" + locked.getListingReference();

        PaymentTransferResult transfer = paymentClient.transfer(
                buyerId, locked.getSellerId(), price, fee, paymentReference);

        if (!transfer.isSuccess()) {
            throw transfer.isRetryable()
                    ? InvestmentDomainException.conflict(
                            transfer.getErrorCode(), transfer.getErrorMessage())
                    : InvestmentDomainException.invalidInput(
                            transfer.getErrorCode(), transfer.getErrorMessage());
        }

        NoteListing sold = transactions.recordPurchase(
                locked.getId(), buyerId, price, fee, transfer.getTransferReference());

        return toResponse(sold, requireNote(sold.getNoteId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NoteListingResponse> browse(Pageable pageable) {
        return listingRepository
                .findByStatusOrderByCreatedAtDesc(NoteListingStatus.OPEN, pageable)
                .map(listing -> toResponse(listing, requireNote(listing.getNoteId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NoteListingResponse> myListings(Pageable pageable) {
        return listingRepository
                .findBySellerIdOrderByCreatedAtDesc(SecurityUtils.getCurrentUserId(), pageable)
                .map(listing -> toResponse(listing, requireNote(listing.getNoteId())));
    }

    /** Phí nền tảng, làm tròn xuống để người bán không bị trừ thừa một đồng vì làm tròn. */
    private BigDecimal platformFee(BigDecimal price) {
        return price.multiply(FEE_RATE).setScale(MONEY_SCALE, RoundingMode.DOWN);
    }

    private String newReference() {
        return "NL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private InvestmentNote requireNote(Long noteId) {
        return noteRepository.findById(noteId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_NOT_FOUND", "Không tìm thấy Note của tin đăng bán này"));
    }

    /**
     * Ghép tin đăng bán với Note và khoản vay gốc.
     *
     * <p>Trạng thái nợ xấu đọc từ Note <em>hiện tại</em>, không từ cờ chụp lúc đăng: Note có thể
     * chuyển sang nợ xấu sau khi đã treo bán, và người mua cần biết tình trạng lúc họ mua.</p>
     */
    private NoteListingResponse toResponse(NoteListing listing, InvestmentNote note) {
        boolean defaulted = note.getStatus() == NoteStatus.DEFAULTED;
        BigDecimal estimatedFee = platformFee(listing.getAskingPrice());

        // Hạng tín dụng thuộc niêm yết gốc, không lưu lại trên Note; đọc kèm để người mua đánh
        // giá được rủi ro mà không phải gọi thêm một lượt sang sàn sơ cấp.
        String creditGrade = marketListingRepository.findById(note.getListingId())
                .map(MarketListing::getCreditGrade)
                .orElse(null);

        return new NoteListingResponse(
                listing.getListingReference(),
                note.getNoteNumber(),
                note.getLoanId(),
                listing.getSellerId(),
                listing.getAskingPrice().toPlainString(),
                note.getOutstandingPrincipal().toPlainString(),
                defaulted ? "Khoản vay gốc đang trong tình trạng nợ xấu" : null,
                defaulted,
                note.getAnnualInterestRate().toPlainString(),
                note.getTermMonths(),
                creditGrade,
                estimatedFee.toPlainString(),
                listing.getAskingPrice().subtract(estimatedFee).toPlainString(),
                listing.getStatus().name(),
                listing.getBuyerId(),
                listing.getSoldPrice() == null ? null : listing.getSoldPrice().toPlainString(),
                listing.getPlatformFee() == null ? null : listing.getPlatformFee().toPlainString(),
                listing.getSellerProceeds() == null ? null : listing.getSellerProceeds().toPlainString(),
                listing.getSoldAt(),
                listing.getCreatedAt());
    }
}
