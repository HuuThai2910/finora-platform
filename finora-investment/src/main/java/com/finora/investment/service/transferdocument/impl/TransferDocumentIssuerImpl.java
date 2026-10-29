package com.finora.investment.service.transferdocument.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.common.document.pades.PadesSignatureException;
import com.finora.investment.client.UserIdentityClient.UserIdentityUnavailableException;
import com.finora.investment.client.UserIdentityClient;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.note.InvestmentNote;
import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.domain.secondary.NoteTransfer;
import com.finora.investment.domain.transferdocument.NoteTransferDocument;
import com.finora.investment.messaging.event.NoteTransferDocumentIssuedEventData;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.InvestmentNoteRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteTransferRepository;
import com.finora.investment.service.transferdocument.TransferDocumentContent;
import com.finora.investment.service.transferdocument.TransferDocumentIssuer;
import com.finora.investment.service.transferdocument.TransferDocumentPdfRenderer;
import com.finora.investment.service.transferdocument.TransferDocumentSeal;
import com.finora.investment.service.transferdocument.TransferDocumentStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lập giấy xác nhận chuyển nhượng cho các lần khớp đã thanh toán: chụp dữ liệu giao dịch, lấy danh tính hai bên từ
 * User, dựng PDF, đóng dấu số rồi giao cho {@link TransferDocumentStateService#complete} lưu cùng event neo hash.
 *
 * <p>Không có transaction bao ngoài: mỗi bước đọc/ghi DB tự mở transaction ngắn, phần gọi mạng và ký nằm giữa.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferDocumentIssuerImpl implements TransferDocumentIssuer {

    private final TransferDocumentStateService stateService;
    private final BookTradeRepository tradeRepository;
    private final NoteTransferRepository transferRepository;
    private final InvestmentNoteRepository noteRepository;
    private final MarketListingRepository listingRepository;
    private final UserIdentityClient userIdentityClient;
    private final TransferDocumentPdfRenderer renderer;
    private final TransferDocumentSeal seal;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void issueDue(int batchSize) {
        stateService.enqueueSettled(batchSize);
        for (Long documentId : stateService.dueIds(batchSize)) {
            stateService.claim(documentId).ifPresent(tradeId -> issue(documentId, tradeId));
        }
    }

    private void issue(Long documentId, Long tradeId) {
        try {
            // /M của chữ ký chỉ có độ phân giải giây; cắt sẵn để PDF, event và DB cùng một thời điểm.
            Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            TransferDocumentContent content = content(tradeId, issuedAt);
            byte[] pdf = seal.seal(renderer.render(content), issuedAt);
            String hash = sha256Hex(pdf);
            JsonNode payload = objectMapper.valueToTree(new NoteTransferDocumentIssuedEventData(
                    content.documentNumber(), content.tradeReference(), content.loanApplicationId(),
                    content.listingId(), hash, NoteTransferDocumentIssuedEventData.HASH_ALGORITHM, issuedAt));
            stateService.complete(documentId, pdf, hash, payload, seal.subjectCommonName(), issuedAt);
        } catch (UserIdentityUnavailableException exception) {
            stateService.fail(documentId, exception.code());
        } catch (PadesSignatureException exception) {
            log.error("Không đóng dấu được giấy xác nhận chuyển nhượng: tradeId={}, code={}",
                    tradeId, exception.getCode(), exception);
            stateService.fail(documentId, exception.getCode());
        } catch (RuntimeException exception) {
            log.error("Không lập được giấy xác nhận chuyển nhượng: tradeId={}", tradeId, exception);
            stateService.fail(documentId, "DOCUMENT_BUILD_FAILED: " + exception.getClass().getSimpleName());
        }
    }

    TransferDocumentContent content(Long tradeId, Instant issuedAt) {
        BookTrade trade = tradeRepository.findById(tradeId).orElseThrow();
        MarketListing listing = listingRepository.findById(trade.getListingId()).orElseThrow();
        List<NoteTransfer> transfers = transferRepository.findByTradeIdOrderByNoteIdAsc(tradeId);
        Map<Long, InvestmentNote> notes = noteRepository.findAllById(
                        transfers.stream().map(NoteTransfer::getNoteId).toList()).stream()
                .collect(Collectors.toMap(InvestmentNote::getId, Function.identity()));
        List<TransferDocumentContent.NoteLine> lines = transfers.stream()
                .map(transfer -> new TransferDocumentContent.NoteLine(
                        notes.containsKey(transfer.getNoteId())
                                ? notes.get(transfer.getNoteId()).getNoteNumber()
                                : "#" + transfer.getNoteId(),
                        transfer.getOutstandingAtTransfer(), transfer.getPrice(),
                        Boolean.TRUE.equals(transfer.getDefaultedAtTransfer())))
                .toList();
        return new TransferDocumentContent(
                NoteTransferDocument.NUMBER_PREFIX + trade.getTradeReference(),
                trade.getTradeReference(),
                trade.getExecutedAt(),
                trade.getSettledAt(),
                issuedAt,
                listing.getLoanId(),
                listing.getId(),
                listing.getContractNumber(),
                listing.getAnnualInterestRate(),
                listing.getTermMonths(),
                party(trade.getSellerId()),
                party(trade.getBuyerId()),
                trade.getPricePermille(),
                trade.getQuantity(),
                trade.getAmount(),
                trade.getPlatformFee(),
                trade.getSellerProceeds(),
                trade.getOutstandingTotal(),
                Boolean.TRUE.equals(trade.getDefaulted()),
                lines,
                seal.demo());
    }

    private TransferDocumentContent.Party party(String investorId) {
        UserIdentityClient.UserIdentity identity = userIdentityClient.identity(investorId);
        return new TransferDocumentContent.Party(investorId, identity.fullName(), identity.maskedIdNumber());
    }

    static String sha256Hex(byte[] content) {
        try {
            // Hex chữ thường: Blockchain chỉ nhận documentHash dạng ^[0-9a-f]{64}$.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM không có SHA-256", exception);
        }
    }
}
