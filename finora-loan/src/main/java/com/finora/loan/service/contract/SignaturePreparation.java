package com.finora.loan.service.contract;

/**
 * Snapshot tối thiểu đã được Loan kiểm tra trước khi gọi provider ngoài transaction.
 *
 * @param pades hợp đồng ký PAdES nhúng PDF (R5); {@code false} là luồng bằng chứng tách rời trước R5
 */
public record SignaturePreparation(
        String contractNumber,
        String borrowerId,
        String documentHash,
        String pdfDocumentHash,
        boolean pades
) {
}
