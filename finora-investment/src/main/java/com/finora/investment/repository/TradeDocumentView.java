package com.finora.investment.repository;

import com.finora.investment.domain.transferdocument.enums.NoteTransferDocumentStatus;

import java.time.Instant;

/** Giấy xác nhận của một lần khớp, chỉ các cột mô tả (không nạp bytes PDF). */
public record TradeDocumentView(String tradeReference, String documentNumber, NoteTransferDocumentStatus status,
                                Instant issuedAt) {
}
