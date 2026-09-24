package com.finora.investment.service.impl;

import com.finora.investment.service.NoteIssuanceService;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.common.enums.investment.CommitmentStatus;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.dto.response.NoteResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.mapper.InvestmentMapper;
import com.finora.investment.repository.InvestmentCommitmentRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xé nhỏ nguồn vốn thành các Note (A2.3/D2) — phần D2 của task gọi vốn.
 *
 * <p><b>Vì sao phải xé nhỏ.</b> Một khoản vay 100 triệu do 8 người góp sẽ tạo ra 8 phần vốn
 * kích thước khác nhau. Nếu giữ nguyên như vậy, người góp 30 triệu muốn bán lại 5 triệu trên
 * chợ thứ cấp (E1) sẽ phải tách phần vốn ngay lúc bán, kéo theo tách cả lịch phân bổ dòng
 * tiền đang chạy. Bằng cách phát hành sẵn 30 Note mệnh giá 1 triệu, việc bán lại chỉ là
 * chuyển quyền sở hữu 5 Note — không đụng gì tới phần còn lại.</p>
 *
 * <p><b>Thời điểm phát hành.</b> Note chỉ ra đời sau khi tiền đã thật sự chuyển cho người vay
 * (F05 bước 7). Trước đó nhà đầu tư mới có cam kết và tiền đang bị giữ, chưa phải quyền sở
 * hữu — nếu giải ngân thất bại thì tiền quay về ví và không có Note nào từng tồn tại.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteIssuanceServiceImpl implements NoteIssuanceService {

    private final MarketListingRepository listingRepository;
    private final InvestmentCommitmentRepository commitmentRepository;
    private final InvestmentNoteRepository noteRepository;
    private final InvestmentMapper mapper;

    /**
     * Khóa toàn bộ phần vốn của một khoản vay trước khi Payment bắt tiền thật (F05 bước 2).
     *
     * <p>Sau bước này nhà đầu tư không hủy lệnh được nữa. Chạy lại trên khoản vay đã khóa
     * không phải lỗi — Saga có thể lặp lại bước này sau khi khởi động lại.</p>
     *
     * @return số phần vốn vừa được khóa trong lần gọi này
     */
    @Override
    @Transactional
    public int finalizeCommitments(Long listingId) {
        MarketListing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay trên sàn"));

        List<InvestmentCommitment> commitments =
                commitmentRepository.findByListingIdAndStatus(listing.getId(), CommitmentStatus.ACTIVE);

        Instant now = Instant.now();
        int locked = 0;
        for (InvestmentCommitment commitment : commitments) {
            if (commitment.getStatus() == CommitmentStatus.ACTIVE) {
                commitment.setStatus(CommitmentStatus.FINALIZED);
                commitment.setFinalizedAt(now);
                commitment.setUpdatedAt(now);
                locked++;
            }
        }

        log.info("Khóa phần vốn để giải ngân: listingId={}, locked={}", listingId, locked);
        return locked;
    }

    /**
     * Phát hành Note cho toàn bộ phần vốn đã khóa của một khoản vay.
     *
     * <p>Idempotent theo commitment: commitment nào đã có Note thì bỏ qua, nên Saga gọi lại
     * sau khi lỗi giữa chừng sẽ chỉ phát hành phần còn thiếu, không nhân đôi quyền sở hữu
     * (F05 failure — "Note activation lỗi sau disbursement → Saga retry/repair").</p>
     *
     * @return tổng số Note vừa phát hành trong lần gọi này
     */
    @Override
    @Transactional
    public int activateNotes(Long listingId) {
        MarketListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "LISTING_NOT_FOUND", "Không tìm thấy khoản vay trên sàn"));

        List<InvestmentCommitment> commitments =
                commitmentRepository.findByListingIdAndStatus(listing.getId(), CommitmentStatus.FINALIZED);

        if (commitments.isEmpty()) {
            throw InvestmentDomainException.conflict(
                    "NO_FINALIZED_COMMITMENT",
                    "Chưa có phần vốn nào được khóa để phát hành Note"
            );
        }

        Instant now = Instant.now();
        List<InvestmentNote> issued = new ArrayList<>();

        for (InvestmentCommitment commitment : commitments) {
            // Đã phát hành ở lần chạy trước: bỏ qua để không tạo Note trùng.
            if (noteRepository.existsByCommitmentId(commitment.getId())) {
                continue;
            }
            issued.addAll(splitIntoNotes(listing, commitment, now));
        }

        if (issued.isEmpty()) {
            log.info("Note đã được phát hành trước đó: listingId={}", listingId);
            return 0;
        }

        noteRepository.saveAll(issued);

        log.info("Phát hành Note: listingId={}, loanId={}, noteCount={}",
                listing.getId(), listing.getLoanId(), issued.size());
        return issued.size();
    }

    /**
     * Chia một phần vốn thành các Note bằng nhau.
     *
     * <p>Số Note và mệnh giá lấy từ commitment chứ không tính lại từ listing: mệnh giá của
     * listing có thể đã đổi sau khi nhà đầu tư đặt lệnh, và cái đã cam kết mới là cái phải
     * được tôn trọng.</p>
     */
    private List<InvestmentNote> splitIntoNotes(
            MarketListing listing,
            InvestmentCommitment commitment,
            Instant now
    ) {
        int count = commitment.getNoteCount();
        List<InvestmentNote> notes = new ArrayList<>(count);

        for (int sequence = 1; sequence <= count; sequence++) {
            notes.add(InvestmentNote.builder()
                    .noteNumber(buildNoteNumber(listing.getLoanId(), commitment.getId(), sequence))
                    .commitmentId(commitment.getId())
                    .listingId(listing.getId())
                    .loanId(listing.getLoanId())
                    .investorId(commitment.getInvestorId())
                    .principalAmount(commitment.getNoteDenomination())
                    .outstandingPrincipal(commitment.getNoteDenomination())
                    .principalRepaid(java.math.BigDecimal.ZERO)
                    .interestReceived(java.math.BigDecimal.ZERO)
                    .annualInterestRate(listing.getAnnualInterestRate())
                    .termMonths(listing.getTermMonths())
                    .sequenceNumber(sequence)
                    .status(com.finora.common.enums.investment.NoteStatus.ACTIVE)
                    .issuedAt(now)
                    .createdBy(commitment.getInvestorId())
                    .updatedBy(commitment.getInvestorId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        return notes;
    }

    /**
     * Mã Note ổn định theo khoản vay, phần vốn và số thứ tự.
     *
     * <p>Sinh từ dữ liệu sẵn có thay vì số ngẫu nhiên để lần chạy lại của Saga tạo ra đúng
     * cùng một mã, và unique constraint chặn được bản ghi trùng.</p>
     */
    private String buildNoteNumber(Long loanId, Long commitmentId, int sequence) {
        return "NOTE-%d-%d-%04d".formatted(loanId, commitmentId, sequence);
    }

    /** Danh sách Note của một phần vốn, dùng cho màn hình chi tiết khoản đầu tư. */
    @Override
    @Transactional(readOnly = true)
    public List<NoteResponse> notesOfCommitment(Long commitmentId) {
        return noteRepository.findByCommitmentId(commitmentId).stream()
                .map(mapper::toNoteResponse)
                .toList();
    }
}
