package com.finora.blockchain.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.finora.blockchain.domain.proof.ProofReferenceType;
import com.finora.blockchain.domain.proof.ProofType;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bốn mốc nghiệp vụ được neo hash lên ledger (R6, W3D D52) và cách lấy aggregate từ {@code data} của event v1 đã có;
 * không cần producer thêm field. Mốc thứ năm là giấy xác nhận chuyển nhượng Note (PDF ký PAdES) Investment cấp sau
 * khi giao dịch sổ lệnh tất toán. Mỗi mốc chỉ có một proof logic theo {@code proofType + aggregate}.
 */
public enum ProofMilestone {

    /** Hợp đồng đủ chữ ký mọi bên: {@code documentHash} (PDF ký chung) + {@code pdfReceiptHash} (bằng chứng ký). */
    CONTRACT_SIGNED("LoanContractActivated", 1, "finora-loan", ProofType.CONTRACT_DOCUMENT) {
        @Override
        Aggregate aggregate(JsonNode data) {
            requireText(data, "documentHash");
            requireText(data, "pdfReceiptHash");
            return new Aggregate(ProofReferenceType.LOAN_CONTRACT.aggregateType(), requireText(data, "contractNumber"));
        }
    },
    /** Giải ngân hoàn tất (Payment đã chuyển tiền, Fineract đã ghi): saga + paymentReference + số tiền. */
    LOAN_DISBURSED("LoanDisbursed", 1, "finora-loan", ProofType.DISBURSEMENT) {
        @Override
        Aggregate aggregate(JsonNode data) {
            requireText(data, "contractNumber");
            requireText(data, "paymentReference");
            requireText(data, "amount");
            String sagaId = requireText(data, "sagaId");
            try {
                UUID.fromString(sagaId);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("LoanDisbursed.sagaId không phải UUID", exception);
            }
            return new Aggregate(ProofReferenceType.DISBURSEMENT_SAGA.aggregateType(), sagaId);
        }
    },
    /** Một lần trả nợ đã thu, ghi core và chia cho chủ Note. */
    REPAYMENT_DISTRIBUTED("RepaymentDistributed", 1, "finora-payment", ProofType.REPAYMENT) {
        @Override
        Aggregate aggregate(JsonNode data) {
            return new Aggregate(ProofReferenceType.REPAYMENT.aggregateType(), requireText(data, "repaymentId"));
        }
    },
    /** Phát hành Note sau giải ngân ({@code ISSUED}, một lần mỗi listing) hoặc chuyển nhượng trên chợ thứ cấp. */
    NOTE_OWNERSHIP_CHANGED("NoteOwnershipChanged", 1, "finora-investment", ProofType.NOTE_OWNERSHIP) {
        @Override
        Aggregate aggregate(JsonNode data) {
            JsonNode notes = data.path("notes");
            if (!notes.isArray() || notes.isEmpty()) {
                throw new IllegalArgumentException("NoteOwnershipChanged phải có danh sách notes");
            }
            String reason = requireText(data, "reason");
            return switch (reason) {
                case "ISSUED" -> new Aggregate(ProofReferenceType.NOTE_ISSUANCE.aggregateType(),
                        Long.toString(requirePositiveLong(data, "listingId")));
                case "TRANSFERRED" -> new Aggregate(ProofReferenceType.NOTE_TRANSFER.aggregateType(),
                        requireText(data, "reference"));
                default -> throw new IllegalArgumentException("NoteOwnershipChanged.reason không được hỗ trợ: " + reason);
            };
        }
    },
    /**
     * Giấy xác nhận chuyển nhượng Note đã ký PAdES: {@code documentHash} là SHA-256 của chính file PDF đã ký, nên
     * người nhận giấy tự băm file để đối chiếu. Aggregate theo {@code tradeReference} (một giấy mỗi giao dịch).
     */
    NOTE_TRANSFER_DOCUMENT_ISSUED("NoteTransferDocumentIssued", 1, "finora-investment",
            ProofType.NOTE_TRANSFER_DOCUMENT) {
        @Override
        Aggregate aggregate(JsonNode data) {
            requireText(data, "documentNumber");
            if (!SHA256_HEX.matcher(requireText(data, "documentHash")).matches()) {
                throw new IllegalArgumentException("NoteTransferDocumentIssued.documentHash phải là 64 ký tự hex");
            }
            if (!"SHA-256".equals(requireText(data, "hashAlgorithm"))) {
                throw new IllegalArgumentException("NoteTransferDocumentIssued.hashAlgorithm phải là SHA-256");
            }
            return new Aggregate(ProofReferenceType.NOTE_TRANSFER_DOCUMENT.aggregateType(),
                    requireText(data, "tradeReference"));
        }
    };

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    private final String eventType;
    private final int eventVersion;
    private final String sourceService;
    private final ProofType proofType;

    ProofMilestone(String eventType, int eventVersion, String sourceService, ProofType proofType) {
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.sourceService = sourceService;
        this.proofType = proofType;
    }

    abstract Aggregate aggregate(JsonNode data);

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public String sourceService() {
        return sourceService;
    }

    public ProofType proofType() {
        return proofType;
    }

    record Aggregate(String type, String id) {
    }

    static String requireText(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Event thiếu field bắt buộc: " + field);
        }
        return value.textValue().trim();
    }

    static long requirePositiveLong(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.canConvertToExactIntegral() || !value.canConvertToLong() || value.asLong() < 1) {
            throw new IllegalArgumentException("Event thiếu số nguyên dương: " + field);
        }
        return value.asLong();
    }
}
