package com.finora.blockchain.domain.proof;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Tham chiếu nghiệp vụ dùng để tra proof trong explorer. {@code LOAN_APPLICATION} gom mọi mốc của một khoản vay; các
 * loại còn lại khớp đúng aggregate mà proof neo (ví dụ {@code LOAN_CONTRACT} = {@code LoanContract/<số hợp đồng>}).
 */
public enum ProofReferenceType {
    LOAN_APPLICATION(null),
    LOAN_CONTRACT("LoanContract"),
    DISBURSEMENT_SAGA("DisbursementSaga"),
    REPAYMENT("Repayment"),
    NOTE_ISSUANCE("NoteIssuance"),
    NOTE_TRANSFER("NoteTransfer");

    private final String aggregateType;

    ProofReferenceType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    /** Tên aggregate lưu trong {@code aggregate_type}; null với {@code LOAN_APPLICATION} (tra theo cột khoản vay). */
    public String aggregateType() {
        return aggregateType;
    }

    public static Optional<ProofReferenceType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.name().equals(normalized)).findFirst();
    }
}
