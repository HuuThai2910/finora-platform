package com.finora.investment.service.impl;

import com.finora.common.enums.investment.NoteStatus;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.secondary.NoteListing;
import com.finora.investment.domain.secondary.NoteListingStatus;
import com.finora.investment.domain.secondary.NoteTransfer;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.NoteListingRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.service.SecondaryMarketTransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecondaryMarketTransactionServiceImpl implements SecondaryMarketTransactionService {

    private final NoteListingRepository listingRepository;
    private final NoteTransferRepository transferRepository;
    private final InvestmentNoteRepository noteRepository;

    @Override
    @Transactional
    public NoteListing lockAndValidateForPurchase(String listingReference, String buyerId) {
        NoteListing listing = listingRepository.findByListingReference(listingReference)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_LISTING_NOT_FOUND", "Không tìm thấy tin đăng bán này"));

        // Đọc lại dưới khóa: giữa lúc tra theo mã và lúc kiểm tra, người khác có thể đã mua.
        NoteListing locked = listingRepository.findByIdForUpdate(listing.getId())
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_LISTING_NOT_FOUND", "Không tìm thấy tin đăng bán này"));

        if (locked.getStatus() != NoteListingStatus.OPEN) {
            throw InvestmentDomainException.conflict(
                    "NOTE_LISTING_NOT_OPEN",
                    locked.getStatus() == NoteListingStatus.SOLD
                            ? "Note này vừa được người khác mua"
                            : "Tin đăng bán đã được người bán rút lại");
        }

        // Mua Note của chính mình không đổi quyền sở hữu nhưng vẫn sinh giao dịch tiền và bản
        // ghi lịch sử — đó là đường làm giả khối lượng giao dịch trên sàn.
        if (locked.getSellerId().equals(buyerId)) {
            throw InvestmentDomainException.invalidInput(
                    "CANNOT_BUY_OWN_NOTE", "Không thể mua Note do chính mình đăng bán");
        }

        InvestmentNote note = requireNote(locked.getNoteId());

        // Chủ sở hữu thật phải khớp người đăng bán. Lệch nghĩa là Note đã đổi chủ bằng đường
        // khác trong lúc tin còn treo — chặn lại thay vì chuyển tiền cho người không còn sở hữu.
        if (!note.getInvestorId().equals(locked.getSellerId())) {
            throw InvestmentDomainException.conflict(
                    "NOTE_OWNER_CHANGED",
                    "Note đã đổi chủ sở hữu sau khi đăng bán, tin này không còn hiệu lực");
        }

        if (note.getStatus() == NoteStatus.CLOSED) {
            throw InvestmentDomainException.conflict(
                    "NOTE_ALREADY_CLOSED", "Note đã tất toán nên không còn gì để chuyển nhượng");
        }

        // Trần giá kiểm lại ở đây chứ không tin vào con số chụp lúc đăng: người vay có thể đã
        // trả nợ trong lúc tin treo, làm dư nợ giảm xuống dưới giá đang treo.
        if (locked.getAskingPrice().compareTo(note.getOutstandingPrincipal()) > 0) {
            throw InvestmentDomainException.conflict(
                    "ASKING_PRICE_ABOVE_OUTSTANDING",
                    "Dư nợ gốc của Note đã giảm xuống dưới giá đang treo; người bán cần đăng lại giá mới");
        }

        return locked;
    }

    @Override
    @Transactional
    public NoteListing recordPurchase(
            Long listingId,
            String buyerId,
            BigDecimal price,
            BigDecimal platformFee,
            String paymentReference) {

        // Tiền đã chuyển xong ở bước trước. Nếu bản ghi chuyển nhượng đã tồn tại thì lần gọi
        // này là một lần chạy lại — trả về trạng thái hiện có thay vì ghi lần hai.
        var existing = transferRepository.findByNoteListingId(listingId);
        if (existing.isPresent()) {
            log.info("Bỏ qua ghi nhận chuyển nhượng đã xử lý: noteListingId={}", listingId);
            return listingRepository.findById(listingId)
                    .orElseThrow(() -> InvestmentDomainException.notFound(
                            "NOTE_LISTING_NOT_FOUND", "Không tìm thấy tin đăng bán này"));
        }

        NoteListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_LISTING_NOT_FOUND", "Không tìm thấy tin đăng bán này"));

        InvestmentNote note = requireNote(listing.getNoteId());
        String sellerId = listing.getSellerId();
        Instant now = Instant.now();

        // Đổi chủ sở hữu: từ đây gốc và lãi của Note thuộc người mua. Người vay không bị ảnh
        // hưởng — họ vẫn trả đúng lịch, chỉ đích đến của tiền đổi.
        note.setInvestorId(buyerId);
        note.setUpdatedBy(buyerId);
        note.setUpdatedAt(now);
        noteRepository.save(note);

        listing.setStatus(NoteListingStatus.SOLD);
        listing.setBuyerId(buyerId);
        listing.setSoldAt(now);
        listing.setSoldPrice(price);
        listing.setPlatformFee(platformFee);
        listing.setSellerProceeds(price.subtract(platformFee));
        listing.setPaymentReference(paymentReference);
        listing.setUpdatedBy(buyerId);
        listing.setUpdatedAt(now);
        listingRepository.save(listing);

        transferRepository.save(NoteTransfer.builder()
                .noteId(note.getId())
                .noteListingId(listing.getId())
                .sellerId(sellerId)
                .buyerId(buyerId)
                .price(price)
                .platformFee(platformFee)
                .sellerProceeds(price.subtract(platformFee))
                .outstandingAtTransfer(note.getOutstandingPrincipal())
                .defaultedAtTransfer(note.getStatus() == NoteStatus.DEFAULTED)
                .paymentReference(paymentReference)
                .transferredAt(now)
                .createdBy(buyerId)
                .createdAt(now)
                .build());

        log.info("Chuyển nhượng Note hoàn tất: noteListingId={}, noteId={}", listingId, note.getId());
        return listing;
    }

    private InvestmentNote requireNote(Long noteId) {
        return noteRepository.findById(noteId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "NOTE_NOT_FOUND", "Không tìm thấy Note của tin đăng bán này"));
    }
}
