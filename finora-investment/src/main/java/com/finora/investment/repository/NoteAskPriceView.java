package com.finora.investment.repository;

/** Phần vốn đang nằm trong một lệnh bán và giá của lệnh đó (phần nghìn dư nợ gốc còn lại). */
public record NoteAskPriceView(Long noteId, Integer pricePermille) {
}
