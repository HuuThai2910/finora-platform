package com.finora.investment.service.transferdocument.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.investment.config.TransferDocumentProperties;
import com.finora.investment.domain.listing.MarketListing;
import com.finora.investment.domain.orderbook.BookTrade;
import com.finora.investment.domain.transferdocument.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.NoteTransferDocumentStatus;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.messaging.event.NoteTransferDocumentIssuedEventData;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.MarketListingRepository;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.service.outbox.InvestmentOutboxService;
import com.finora.investment.service.transferdocument.TransferDocumentStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Các bước có transaction của vòng đời giấy xác nhận chuyển nhượng. Dựng PDF, gọi User và ký nằm ngoài các
 * transaction này ({@link TransferDocumentIssuer}) để không giữ khóa dòng trong lúc chờ mạng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferDocumentStateServiceImpl implements TransferDocumentStateService {

    private final NoteTransferDocumentRepository documentRepository;
    private final BookTradeRepository tradeRepository;
    private final MarketListingRepository listingRepository;
    private final InvestmentOutboxService outboxService;
    private final TransferDocumentProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public int enqueueSettled(int batchSize) {
        Instant now = clock.instant();
        List<Long> tradeIds = documentRepository.findSettledTradeIdsWithoutDocument(PageRequest.of(0, batchSize));
        for (Long tradeId : tradeIds) {
            BookTrade trade = tradeRepository.findById(tradeId).orElseThrow();
            MarketListing listing = listingRepository.findById(trade.getListingId()).orElseThrow();
            documentRepository.save(NoteTransferDocument.pending(
                    trade.getId(), trade.getTradeReference(), listing.getLoanId(), trade.getListingId(), now));
        }
        return tradeIds.size();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> dueIds(int batchSize) {
        return documentRepository.findDueIds(clock.instant(), PageRequest.of(0, batchSize));
    }

    @Override
    @Transactional
    public Optional<Long> claim(Long documentId) {
        Instant now = clock.instant();
        return documentRepository.findByIdForUpdate(documentId)
                .filter(document -> document.getStatus() == NoteTransferDocumentStatus.PENDING)
                .filter(document -> !document.getNextAttemptAt().isAfter(now))
                .map(document -> {
                    document.claim(properties.processingLease(), now);
                    return document.getTradeId();
                });
    }

    @Override
    @Transactional
    public void complete(Long documentId, byte[] pdf, String hash, JsonNode payload, String sealSubject,
            Instant issuedAt) {
        NoteTransferDocument document = documentRepository.findByIdForUpdate(documentId).orElseThrow();
        if (document.getStatus() != NoteTransferDocumentStatus.PENDING) {
            log.info("Bỏ qua văn bản không còn chờ lập: documentNumber={}, status={}",
                    document.getDocumentNumber(), document.getStatus());
            return;
        }
        document.markIssued(pdf, hash, payload.toString(), sealSubject, issuedAt);
        // Cùng Kafka key với mọi event của khoản vay, như NoteOwnershipChanged.
        outboxService.record("LOAN", document.getLoanId().toString(),
                NoteTransferDocumentIssuedEventData.EVENT_TYPE, NoteTransferDocumentIssuedEventData.EVENT_VERSION,
                payload);
        log.info("Đã phát hành giấy xác nhận chuyển nhượng: documentNumber={}, tradeReference={}",
                document.getDocumentNumber(), document.getTradeReference());
    }

    @Override
    @Transactional
    public void fail(Long documentId, String error) {
        Instant now = clock.instant();
        documentRepository.findByIdForUpdate(documentId)
                .filter(document -> document.getStatus() == NoteTransferDocumentStatus.PENDING)
                .ifPresent(document -> {
                    if (document.getAttempts() >= properties.maxAttempts()) {
                        document.markFailed(error, now);
                        log.warn("Dừng lập giấy xác nhận chuyển nhượng: documentNumber={}, attempts={}, error={}",
                                document.getDocumentNumber(), document.getAttempts(), error);
                    } else {
                        document.scheduleRetry(error, backoff(document.getAttempts()), now);
                    }
                });
    }

    @Override
    @Transactional
    public void reopen(String tradeReference) {
        NoteTransferDocument document = documentRepository.findByTradeReference(tradeReference)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "TRANSFER_DOCUMENT_NOT_FOUND", "Không tìm thấy giấy xác nhận chuyển nhượng"));
        if (document.getStatus() != NoteTransferDocumentStatus.FAILED) {
            throw InvestmentDomainException.conflict(
                    "TRANSFER_DOCUMENT_NOT_FAILED", "Chỉ thử lại được văn bản đang lỗi");
        }
        document.reopen(clock.instant());
    }

    Duration backoff(int attempts) {
        Duration delay = properties.retryBackoff();
        for (int attempt = 1; attempt < attempts && delay.compareTo(properties.maximumBackoff()) < 0; attempt++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(properties.maximumBackoff()) > 0 ? properties.maximumBackoff() : delay;
    }
}
