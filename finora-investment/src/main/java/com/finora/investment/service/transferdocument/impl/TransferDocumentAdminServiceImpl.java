package com.finora.investment.service.transferdocument.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.domain.transferdocument.entity.NoteTransferDocument;
import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;
import com.finora.investment.dto.response.TransferDocumentAdminResponse;
import com.finora.investment.exception.InvestmentDomainException;
import com.finora.investment.repository.BookTradeRepository;
import com.finora.investment.repository.NoteTransferDocumentRepository;
import com.finora.investment.repository.NoteTransferDocumentRow;
import com.finora.investment.service.transferdocument.TransferDocumentAdminService;
import com.finora.investment.service.transferdocument.TransferDocumentStateService;
import com.finora.investment.util.SearchPatterns;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tra cứu, tải PDF và thử lại giấy xác nhận chuyển nhượng cho quản trị viên. */
@Service
@RequiredArgsConstructor
public class TransferDocumentAdminServiceImpl implements TransferDocumentAdminService {

    private final NoteTransferDocumentRepository noteTransferDocumentRepository;
    private final BookTradeRepository bookTradeRepository;
    private final TransferDocumentStateService transferDocumentStateService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Page<TransferDocumentAdminResponse> list(NoteTransferDocumentStatus status, Pageable pageable) {
        return list(status, null, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TransferDocumentAdminResponse> list(NoteTransferDocumentStatus status, String q, Pageable pageable) {
        String code = SearchPatterns.containsCode(q);
        Page<NoteTransferDocumentRow> rows = code != null
                ? noteTransferDocumentRepository.searchRows(status, code, pageable)
                : status == null
                ? noteTransferDocumentRepository.findRows(pageable)
                : noteTransferDocumentRepository.findRowsByStatus(status, pageable);
        return rows.map(row -> response(row, null));
    }

    @Override
    @Transactional(readOnly = true)
    public TransferDocumentAdminResponse detail(String tradeReference) {
        NoteTransferDocument document = find(tradeReference);
        var trade = bookTradeRepository.findById(document.getTradeId()).orElseThrow();
        NoteTransferDocumentRow row = new NoteTransferDocumentRow(
                document.getDocumentNumber(), document.getTradeReference(), document.getLoanId(),
                document.getListingId(), trade.getSellerId(), trade.getBuyerId(), trade.getQuantity(),
                trade.getAmount(), document.getStatus(), document.getContentHash(), document.getAttempts(),
                document.getLastError(), trade.getExecutedAt(), document.getIssuedAt());
        return response(row, artifact(document.getProofPayload()));
    }

    @Override
    @Transactional(readOnly = true)
    public IssuedPdf pdf(String tradeReference) {
        NoteTransferDocument document = find(tradeReference);
        if (!document.isIssued()) {
            throw InvestmentDomainException.conflict(
                    "TRANSFER_DOCUMENT_NOT_ISSUED", "Giấy xác nhận chuyển nhượng chưa được phát hành");
        }
        return new IssuedPdf(document.getDocumentNumber(), document.contentCopy());
    }

    @Override
    public void retry(String tradeReference) {
        transferDocumentStateService.reopen(tradeReference);
    }

    private NoteTransferDocument find(String tradeReference) {
        return noteTransferDocumentRepository.findByTradeReference(tradeReference)
                .orElseThrow(() -> InvestmentDomainException.notFound(
                        "TRANSFER_DOCUMENT_NOT_FOUND", "Không tìm thấy giấy xác nhận chuyển nhượng"));
    }

    private JsonNode artifact(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("proof_payload của giấy xác nhận không phải JSON", exception);
        }
    }

    private static TransferDocumentAdminResponse response(NoteTransferDocumentRow row, JsonNode artifact) {
        return new TransferDocumentAdminResponse(
                row.documentNumber(), row.tradeReference(), row.loanApplicationId(), row.listingId(),
                row.sellerId(), row.buyerId(), row.quantity(), row.amount().toPlainString(), row.status().name(),
                row.documentHash(), row.attempts(), row.lastError(), row.executedAt(), row.issuedAt(), artifact);
    }
}
