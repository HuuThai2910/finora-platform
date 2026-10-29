package com.finora.user.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.finora.user.client.AiEkycClient.DocumentRequest;
import com.finora.user.client.AiEkycClient.DocumentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Khuôn JSON snake_case giữa finora-user và {@code POST /api/v1/ai/ekyc/document} (EKYC-002 mục 2.1).
 * Dùng mapper chặt (báo lỗi field lạ) để chắc DTO tự bỏ qua field AI thêm sau này.
 */
class AiEkycClientContractTest {

    private static final String RESPONSE = """
            {
              "decision": "AUTO_OK",
              "card_type": "CCCD_CHIP_2021",
              "reasons": ["QR_MRZ_AGREE"],
              "warnings": ["ID_PROVINCE_UNKNOWN"],
              "retake": [{"side": "BACK", "reason": "GLARE"}],
              "fields": {
                "id_number":                {"value": "001095012345", "sources": ["QR", "MRZ"]},
                "full_name":                {"value": "NGUYỄN VĂN A", "sources": ["QR"]},
                "date_of_birth":            {"value": "15/03/1995", "sources": ["QR", "MRZ"]},
                "gender":                   {"value": "Nam", "sources": ["QR", "MRZ"]},
                "address":                  {"value": "Phường 1", "sources": ["QR"]},
                "place_of_origin":          {"value": null, "sources": []},
                "birth_registration_place": {"value": null, "sources": []},
                "issue_date":               {"value": "01/07/2021", "sources": ["QR"]},
                "expiry_date":              {"value": "15/03/2035", "sources": ["MRZ"], "no_expiry": false}
              },
              "checks": {"qr": {"found": true, "by": "CLIENT", "side": "FRONT", "valid": true},
                         "mrz": {"found": true, "valid": true, "engine": "TESSERACT", "doc_number_ok": true},
                         "id_rules_ok": true, "sources_agree": true, "front_back_linked": true},
              "quality": {"front": {"card_found": true, "sharpness": 152.3, "glare_ratio": 0.004, "brightness": 0.55, "ok": true}},
              "portrait": {"found": true, "ied_px": 64.0, "sharpness": 63.1, "embedding_b64": "AAAA"},
              "ai_used": false,
              "thresholds_version": "doc-2026-10-placeholder",
              "debug_extra": {"x": 1}
            }
            """;

    private final ObjectMapper strictMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndAddModules()
            .build();

    @Test
    void readsDocumentResponseFromSnakeCase() throws Exception {
        DocumentResult result = strictMapper.readValue(RESPONSE, DocumentResult.class);

        assertThat(result.decision()).isEqualTo("AUTO_OK");
        assertThat(result.cardType()).isEqualTo("CCCD_CHIP_2021");
        assertThat(result.retake()).singleElement().satisfies(hint -> {
            assertThat(hint.side()).isEqualTo("BACK");
            assertThat(hint.reason()).isEqualTo("GLARE");
        });
        assertThat(result.fields().idNumber().value()).isEqualTo("001095012345");
        assertThat(result.fields().idNumber().sources()).containsExactly("QR", "MRZ");
        assertThat(result.fields().birthRegistrationPlace().value()).isNull();
        assertThat(result.fields().expiryDate().noExpiry()).isFalse();
        assertThat(result.checks().path("id_rules_ok").asBoolean()).isTrue();
        assertThat(result.portrait().found()).isTrue();
        assertThat(result.portrait().iedPx()).isEqualTo(64.0);
        assertThat(result.portrait().embeddingB64()).isEqualTo("AAAA");
        assertThat(result.aiUsed()).isFalse();
        assertThat(result.thresholdsVersion()).isEqualTo("doc-2026-10-placeholder");
    }

    @Test
    void writesDocumentRequestInSnakeCase() throws Exception {
        JsonNode json = strictMapper.valueToTree(new DocumentRequest("FRONT", "BACK", "001095012345|...", "FRONT",
                "IN_APP_CAMERA", "GALLERY", 2));

        assertThat(json.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "front_image_base64", "back_image_base64", "qr_text", "qr_side", "front_source", "back_source",
                "retake_count");
        assertThat(json.get("retake_count").asInt()).isEqualTo(2);
        assertThat(json.get("back_source").asText()).isEqualTo("GALLERY");
    }
}
