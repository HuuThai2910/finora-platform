package com.finora.loan.service.contract;

/** Snapshot tối thiểu đã được Loan kiểm tra trước khi gọi provider ngoài transaction. */
public record SignaturePreparation(
        String contractNumber,
        String borrowerId,
        String documentHash,
        String pdfDocumentHash
) {
}
