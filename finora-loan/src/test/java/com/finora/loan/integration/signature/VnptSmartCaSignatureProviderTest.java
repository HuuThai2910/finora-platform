package com.finora.loan.integration.signature;

import com.finora.loan.config.SignatureProviderProperties;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VnptSmartCaSignatureProviderTest {

    private static final String BASE_URL = "https://rmgateway.vnptit.vn/sca/sp769";
    private static final String TRANSACTION_ID = "FINORA-TX-001";
    private static final String DOCUMENT_ID = "FINORA-DOC-001";

    private MockRestServiceServer server;
    private VnptSmartCaSignatureProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        SignatureProviderProperties properties = new SignatureProviderProperties(
                SignatureProviderType.VNPT_SMART_CA,
                new SignatureProviderProperties.VnptSmartCa(
                        URI.create(BASE_URL), "test-sp", "test-password",
                        true, "test-user", "test-serial", Duration.ofSeconds(3), Duration.ofSeconds(15)));
        provider = new VnptSmartCaSignatureProvider(builder.build(), properties);
    }

    @Test
    void validatesCertificateThenSubmitsAndPollsDetachedSignature() {
        server.expect(requestTo(BASE_URL + "/v1/credentials/get_certificate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.sp_id").value("test-sp"))
                .andExpect(jsonPath("$.user_id").value("test-user"))
                .andExpect(jsonPath("$.serial_number").value("test-serial"))
                .andRespond(withSuccess("""
                        {
                          "status_code": 200,
                          "message": "Success",
                          "data": {
                            "user_certificates": [{
                              "serial_number": "test-serial",
                              "cert_status_code": "VALID"
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE_URL + "/v1/signatures/sign"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transaction_id").value(TRANSACTION_ID))
                .andExpect(jsonPath("$.sign_files[0].doc_id").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.sign_files[0].data_to_be_signed").value("b".repeat(64)))
                .andRespond(withSuccess("""
                        {
                          "status_code": 200,
                          "message": "Success",
                          "data": {"transaction_id": "FINORA-TX-001"}
                        }
                        """, MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE_URL + "/v1/signatures/sign/FINORA-TX-001/status"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "status_code": 200,
                          "message": "Success",
                          "data": {
                            "transaction_id": "FINORA-TX-001",
                            "signatures": [{
                              "doc_id": "FINORA-DOC-001",
                              "signature_value": "detached-signature-value",
                              "timestamp_signature": "2026-09-21T10:00:00Z"
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        SignatureSubmission submission = provider.submit(command());

        assertThat(submission.status()).isEqualTo(SignatureSubmissionStatus.PENDING);
        assertThat(submission.providerTransactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(submission.documentId()).isEqualTo(DOCUMENT_ID);

        SignatureSubmission completed = provider.status(TRANSACTION_ID, DOCUMENT_ID);

        assertThat(completed.status()).isEqualTo(SignatureSubmissionStatus.COMPLETED);
        assertThat(completed.signatureValue()).isEqualTo("detached-signature-value");
        assertThat(completed.timestampSignature()).isEqualTo("2026-09-21T10:00:00Z");
        server.verify();
    }

    @Test
    void mapsProviderRejectionWithoutTreatingItAsCompleted() {
        server.expect(requestTo(BASE_URL + "/v1/signatures/sign/FINORA-TX-001/status"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {
                          "status_code": 400,
                          "message": "sig_user_rejected",
                          "data": {"transaction_id": "FINORA-TX-001"}
                        }
                        """, MediaType.APPLICATION_JSON));

        SignatureSubmission rejected = provider.status(TRANSACTION_ID, DOCUMENT_ID);

        assertThat(rejected.status()).isEqualTo(SignatureSubmissionStatus.REJECTED);
        assertThat(rejected.signatureValue()).isNull();
        server.verify();
    }

    private SignatureCommand command() {
        return new SignatureCommand(
                "LC-ABCDEF0123456789ABCD",
                "BORROWER-001",
                "a".repeat(64),
                "b".repeat(64),
                "sign-key-001",
                TRANSACTION_ID,
                DOCUMENT_ID,
                SignatureMethod.VNPT_SMART_CA);
    }
}
