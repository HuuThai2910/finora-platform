package com.finora.user.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.finora.user.client.AiEkycClient.DocumentFields;
import com.finora.user.client.AiEkycClient.DocumentResult;
import com.finora.user.client.AiEkycClient.FieldReading;
import com.finora.user.client.AiEkycClient.Portrait;
import com.finora.user.client.AiEkycClient.RetakeHint;
import com.finora.user.config.CryptoProperties;
import com.finora.user.domain.EkycAttempt;
import com.finora.user.domain.EkycDocumentDecision;
import com.finora.user.domain.EkycDraftField;
import com.finora.user.domain.EkycEvidence;
import com.finora.user.domain.EkycEvidenceKind;
import com.finora.user.domain.EkycEvidenceStage;
import com.finora.user.domain.EkycIdentityDraft;
import com.finora.user.domain.EkycLivenessResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;

/**
 * Dữ liệu mẫu dùng chung cho test eKYC. Số định danh và họ tên là dữ liệu tổng hợp, không phải PII thật:
 * {@code 001095012345} khớp cấu trúc nam sinh năm 1995 như mẫu MRZ tổng hợp trong đề xuất.
 */
public final class EkycTestData {

    public static final String ID_NUMBER = "001095012345";
    public static final String OTHER_ID_NUMBER = "036094009999";
    public static final String HMAC_SECRET = "test-hmac-secret";
    public static final String AES_SECRET = "test-aes-secret-32-characters!!";
    public static final String EMBEDDING = "AAAAPwAAAD8AAAA/";

    /** JPEG tối thiểu: chỉ cần magic bytes để qua kiểm tra kiểu file. */
    public static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
    public static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    public static final String JPEG_BASE64 = Base64.getEncoder().encodeToString(JPEG_BYTES);
    public static final String PNG_BASE64 = Base64.getEncoder().encodeToString(PNG_BYTES);

    private EkycTestData() {
    }

    public static ObjectMapper objectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }

    public static CryptoProperties cryptoProperties() {
        CryptoProperties properties = new CryptoProperties();
        properties.setHmacSecret(HMAC_SECRET);
        properties.setAesSecret(AES_SECRET);
        return properties;
    }

    /** Transaction "giả": chạy callback ngay, commit/rollback không làm gì — đủ cho unit test. */
    public static TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(mock(PlatformTransactionManager.class));
    }

    public static String hash(String idNumber) {
        return CryptoUtils.hmacSha256(idNumber, HMAC_SECRET);
    }

    /** Kết quả AI đọc thẻ CCCD 2021 với QR + MRZ khớp nhau. */
    public static DocumentResult document(String decision, String cardType, String idNumber, String dateOfBirth,
                                          String expiryDate, Boolean noExpiry) {
        DocumentFields fields = new DocumentFields(
                new FieldReading(idNumber, List.of("QR", "MRZ"), null),
                new FieldReading("NGUYỄN VĂN A", List.of("QR"), null),
                new FieldReading(dateOfBirth, List.of("QR", "MRZ"), null),
                new FieldReading("Nam", List.of("QR", "MRZ"), null),
                new FieldReading("Phường 1, Quận 3, TP. Hồ Chí Minh", List.of("AI"), null),
                new FieldReading(null, List.of(), null),
                new FieldReading(null, List.of(), null),
                new FieldReading("01/07/2021", List.of("QR"), null),
                new FieldReading(expiryDate, List.of("MRZ"), noExpiry));
        return new DocumentResult(decision, cardType, List.of("QR_MRZ_AGREE"), List.of("ID_PROVINCE_UNKNOWN"),
                List.of(), fields, objectMapper().createObjectNode().put("id_rules_ok", true), null,
                new Portrait(true, 64.0, 63.1, EMBEDDING), false, "doc-2026-10-placeholder");
    }

    public static DocumentResult okDocument() {
        return document("AUTO_OK", "CCCD_CHIP_2021", ID_NUMBER, "15/03/1995", "15/03/2035", false);
    }

    public static DocumentResult retakeDocument(List<RetakeHint> hints) {
        return new DocumentResult("RETAKE", "CCCD_CHIP_2021", List.of(), List.of(), hints, null, null, null,
                new Portrait(false, null, null, null), false, "doc-2026-10-placeholder");
    }

    /** Bản nháp như sau bước đọc thẻ; {@code liveness} null nghĩa là chưa quay video. */
    public static EkycIdentityDraft draft(UUID attemptId, EkycDocumentDecision decision, EkycLivenessResult liveness) {
        Map<String, EkycDraftField> fields = new LinkedHashMap<>();
        fields.put("idNumber", new EkycDraftField(ID_NUMBER, List.of("QR", "MRZ")));
        fields.put("fullName", new EkycDraftField("NGUYỄN VĂN A", List.of("QR")));
        fields.put("dateOfBirth", new EkycDraftField("15/03/1995", List.of("QR", "MRZ")));
        fields.put("gender", new EkycDraftField("Nam", List.of("QR", "MRZ")));
        fields.put("address", new EkycDraftField("Phường 1, Quận 3, TP. Hồ Chí Minh", List.of("AI")));
        fields.put("placeOfOrigin", new EkycDraftField(null, List.of()));
        fields.put("birthRegistrationPlace", new EkycDraftField("UBND Phường 1", List.of("AI")));
        fields.put("issueDate", new EkycDraftField("01/07/2021", List.of("QR")));
        fields.put("expiryDate", new EkycDraftField("15/03/2035", List.of("MRZ")));
        return new EkycIdentityDraft(attemptId, "CCCD_CHIP_2021", decision, List.of("QR_MRZ_AGREE"),
                List.of("ID_PROVINCE_UNKNOWN"), null, "doc-2026-10-placeholder", fields, false, EMBEDDING,
                liveness, liveness == null ? null : 0.52,
                liveness == null ? List.of() : List.of("ACTIONS_MATCH", "FACE_MATCH_HIGH"),
                liveness == null ? null : "liveness-2026-10-placeholder", liveness == null ? null : "VIDEO",
                liveness == null ? 0 : 1, List.of());
    }

    public static EkycAttempt attempt(Long id, UUID publicId, Long userProfileId, EkycDocumentDecision decision,
                                      Instant now) {
        EkycAttempt attempt = EkycAttempt.start(publicId, userProfileId, 5L, "CCCD_CHIP_2021", decision,
                "[\"QR_MRZ_AGREE\"]", "doc-2026-10-placeholder", now);
        ReflectionTestUtils.setField(attempt, "id", id);
        ReflectionTestUtils.setField(attempt, "version", 0L);
        return attempt;
    }

    public static EkycEvidence evidence(Long id, Long attemptId, EkycEvidenceKind kind, String key,
                                        EkycEvidenceStage stage, Instant now) {
        EkycEvidence evidence = EkycEvidence.stored(attemptId, kind, null, key, "c".repeat(64), 1234L,
                kind == EkycEvidenceKind.RESULT ? "application/json" : "image/jpeg", stage, now);
        ReflectionTestUtils.setField(evidence, "id", id);
        return evidence;
    }
}
