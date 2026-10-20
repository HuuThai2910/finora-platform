package com.finora.blockchain.service.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.blockchain.domain.proof.ProofHashScheme;
import org.junit.jupiter.api.Test;

class ProofPayloadHasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Data của LoanContractActivated.v1 theo đúng thứ tự field Loan ghi; vector tính bằng json.dumps(sort_keys) của Python. */
    private static final String CONTRACT_DATA = """
            {"loanApplicationId":123,"applicationNumber":"LA-2026-000123","contractNumber":"LC-2026-000123",
             "listingId":456,"documentHash":"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4",
             "pdfReceiptHash":"2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
             "allocationVersion":1,
             "allocationHash":"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9",
             "activatedAt":"2026-10-17T03:00:00Z"}
            """;

    @Test
    void canonicalHashMatchesAnIndependentImplementation() throws Exception {
        JsonNode data = MAPPER.readTree(CONTRACT_DATA);

        assertThat(ProofPayloadHasher.canonical(data)).isEqualTo("{\"activatedAt\":\"2026-10-17T03:00:00Z\","
                + "\"allocationHash\":\"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9\","
                + "\"allocationVersion\":1,\"applicationNumber\":\"LA-2026-000123\",\"contractNumber\":\"LC-2026-000123\","
                + "\"documentHash\":\"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4\","
                + "\"listingId\":456,\"loanApplicationId\":123,"
                + "\"pdfReceiptHash\":\"2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae\"}");
        assertThat(ProofPayloadHasher.hash(data, ProofHashScheme.CANONICAL_JSON_V2, MAPPER))
                .isEqualTo("efee2119b4714f9547d8781865a70ae390580f1a067766fd50ccb846e692a729");
    }

    @Test
    void fieldOrderWhitespaceAndNullMembersDoNotChangeTheHash() throws Exception {
        // JSONB của outbox sắp lại key; serializer NON_NULL bỏ field null: cả hai vẫn ra cùng hash.
        JsonNode reordered = MAPPER.readTree("""
                { "pdfReceiptHash" : "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
                  "activatedAt":"2026-10-17T03:00:00Z", "listingId":456, "loanApplicationId":123,
                  "contractNumber":"LC-2026-000123", "applicationNumber":"LA-2026-000123", "allocationVersion":1,
                  "allocationHash":"fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9",
                  "documentHash":"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4",
                  "optionalField": null }
                """);

        assertThat(ProofPayloadHasher.hash(reordered, ProofHashScheme.CANONICAL_JSON_V2, MAPPER))
                .isEqualTo("efee2119b4714f9547d8781865a70ae390580f1a067766fd50ccb846e692a729");
    }

    @Test
    void escapingAndKeyOrderFollowRfc8785() throws Exception {
        JsonNode tricky = MAPPER.readTree(
                "{\"b\":[3,{\"z\":null,\"y\":\"Nguyễn \\\"A\\\"\\n\\t\\\\\"}],\"a\":null,\"A\":true,\"é\":false,"
                        + "\"_\":\"\\u0001\\u007f\"}");

        assertThat(ProofPayloadHasher.canonical(tricky))
                .isEqualTo("{\"A\":true,\"_\":\"\\u0001\u007f\",\"b\":[3,{\"y\":\"Nguyễn \\\"A\\\"\\n\\t\\\\\"}],\"é\":false}");
        assertThat(ProofPayloadHasher.hash(tricky, ProofHashScheme.CANONICAL_JSON_V2, MAPPER))
                .isEqualTo("ef537ddec7433e8f3b2f2a8b0c51be925207fbbe1863063ae7cd0ec654ca73a2");
    }

    @Test
    void floatingPointAndUnsafeIntegersAreRejectedBecauseMoneyMustBeDecimalStrings() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> ProofPayloadHasher.hash(
                MAPPER.readTree("{\"amount\":1000000.50}"), ProofHashScheme.CANONICAL_JSON_V2, MAPPER));
        assertThatIllegalArgumentException().isThrownBy(() -> ProofPayloadHasher.hash(
                MAPPER.readTree("{\"id\":9007199254740993}"), ProofHashScheme.CANONICAL_JSON_V2, MAPPER));
        assertThatIllegalArgumentException().isThrownBy(() -> ProofPayloadHasher.hash(
                MAPPER.readTree("[1,2]"), ProofHashScheme.CANONICAL_JSON_V2, MAPPER));
    }

    @Test
    void legacySchemeKeepsTheReceivedFieldOrder() throws Exception {
        JsonNode first = MAPPER.readTree("{\"repaymentId\":\"R-1\",\"amount\":\"10.00\"}");
        JsonNode reordered = MAPPER.readTree("{\"amount\":\"10.00\",\"repaymentId\":\"R-1\"}");

        // Đây chính là B51: cùng dữ liệu nhưng khác thứ tự thì hash v1 khác nhau, còn v2 thì không.
        assertThat(ProofPayloadHasher.hash(first, ProofHashScheme.LEGACY_COMPACT_V1, MAPPER))
                .isEqualTo(ProofPayloadHasher.sha256Hex("{\"repaymentId\":\"R-1\",\"amount\":\"10.00\"}".getBytes()))
                .isNotEqualTo(ProofPayloadHasher.hash(reordered, ProofHashScheme.LEGACY_COMPACT_V1, MAPPER));
        assertThat(ProofPayloadHasher.hash(first, ProofHashScheme.CANONICAL_JSON_V2, MAPPER))
                .isEqualTo(ProofPayloadHasher.hash(reordered, ProofHashScheme.CANONICAL_JSON_V2, MAPPER));
    }
}
