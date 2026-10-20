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

    @Test
    void padesReadsSignerCertificateAndChainFromCertData() throws Exception {
        VnptSmartCaSignatureProvider padesProvider = providerWithSerial(HEX_SERIAL);
        java.security.cert.X509Certificate signer = certificate(new java.math.BigInteger(HEX_SERIAL, 16),
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().plusSeconds(86400));
        java.security.cert.X509Certificate ca = certificate(java.math.BigInteger.TEN,
                java.time.Instant.now().minusSeconds(3600), java.time.Instant.now().plusSeconds(86400));
        // Serial in hoa và thêm số 0 đầu: VNPT và BigInteger không in giống nhau nhưng là cùng một chứng thư.
        server.expect(requestTo(BASE_URL + "/v1/credentials/get_certificate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.serial_number").value(HEX_SERIAL))
                .andRespond(withSuccess(certificateResponse(
                        "00" + HEX_SERIAL.toUpperCase(), "Đang hoạt động", mime(signer), base64(ca)),
                        MediaType.APPLICATION_JSON));

        SigningCertificate result = padesProvider.signingCertificate("INVESTOR-001");

        assertThat(result.certificate()).isEqualTo(signer);
        assertThat(result.chain()).containsExactly(ca);
        assertThat(padesProvider.completesSynchronously()).isFalse();
        server.verify();
    }

    @Test
    void padesRejectsWrongSerialExpiredOrRevokedCertificate() throws Exception {
        VnptSmartCaSignatureProvider padesProvider = providerWithSerial(HEX_SERIAL);
        java.time.Instant now = java.time.Instant.now();
        java.security.cert.X509Certificate otherSerial = certificate(java.math.BigInteger.valueOf(77),
                now.minusSeconds(3600), now.plusSeconds(86400));
        java.security.cert.X509Certificate expired = certificate(new java.math.BigInteger(HEX_SERIAL, 16),
                now.minusSeconds(7200), now.minusSeconds(3600));
        java.security.cert.X509Certificate valid = certificate(new java.math.BigInteger(HEX_SERIAL, 16),
                now.minusSeconds(3600), now.plusSeconds(86400));
        server.expect(requestTo(BASE_URL + "/v1/credentials/get_certificate"))
                .andRespond(withSuccess(certificateResponse(HEX_SERIAL, null, base64(otherSerial), null),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/credentials/get_certificate"))
                .andRespond(withSuccess(certificateResponse(HEX_SERIAL, null, base64(expired), null),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/credentials/get_certificate"))
                .andRespond(withSuccess(certificateResponse(HEX_SERIAL, "Đã thu hồi", base64(valid), null),
                        MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> padesProvider.signingCertificate("INVESTOR-001"))
                .isInstanceOf(SmartCaIntegrationException.class)
                .extracting("code").isEqualTo("SMARTCA_CONTRACT_MISMATCH");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> padesProvider.signingCertificate("INVESTOR-001"))
                .isInstanceOf(SmartCaIntegrationException.class)
                .extracting("code").isEqualTo("SMARTCA_CERTIFICATE_NOT_VALID");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> padesProvider.signingCertificate("INVESTOR-001"))
                .isInstanceOf(SmartCaIntegrationException.class)
                .extracting("code").isEqualTo("SMARTCA_CERTIFICATE_NOT_VALID");
        server.verify();
    }

    @Test
    void padesSubmitsHexDigestOfSignedAttributesWithHashSignType() {
        String digest = "c".repeat(64);
        server.expect(requestTo(BASE_URL + "/v1/signatures/sign"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.transaction_id").value("FINORA-PADES-TX-001"))
                .andExpect(jsonPath("$.transaction_desc").value("FINORA LC-ABCDEF0123456789ABCD - chu ky 1"))
                .andExpect(jsonPath("$.sign_files[0].data_to_be_signed").value(digest))
                .andExpect(jsonPath("$.sign_files[0].doc_id").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.sign_files[0].file_type").value("pdf"))
                .andExpect(jsonPath("$.sign_files[0].sign_type").value("hash"))
                .andRespond(withSuccess("""
                        {"status_code": 200, "message": "sig_wait_for_user_confirm",
                         "data": {"transaction_id": "FINORA-PADES-TX-001", "tran_code": "abc"}}
                        """, MediaType.APPLICATION_JSON));

        SignatureSubmission submission = provider.submitDigest(new DigestSignatureCommand(
                "LC-ABCDEF0123456789ABCD", "INVESTOR-001", digest, "FINORA-PADES-TX-001", DOCUMENT_ID,
                "FINORA LC-ABCDEF0123456789ABCD - chu ky 1", SignatureMethod.VNPT_SMART_CA));

        assertThat(submission.status()).isEqualTo(SignatureSubmissionStatus.PENDING);
        assertThat(submission.providerTransactionId()).isEqualTo("FINORA-PADES-TX-001");
        server.verify();
    }

    private static final String HEX_SERIAL = "54010101493c47d39f8a84b30ed55191";

    private VnptSmartCaSignatureProvider providerWithSerial(String serial) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new VnptSmartCaSignatureProvider(builder.build(), new SignatureProviderProperties(
                SignatureProviderType.VNPT_SMART_CA,
                new SignatureProviderProperties.VnptSmartCa(
                        URI.create(BASE_URL), "test-sp", "test-password",
                        true, "test-user", serial, Duration.ofSeconds(3), Duration.ofSeconds(15))));
    }

    private static String certificateResponse(String serial, String status, String certData, String caCert) {
        String statusField = status == null ? "" : "\"cert_status\": \"" + status + "\",";
        String chain = caCert == null ? "null" : "\"" + caCert + "\"";
        return """
                {"status_code": 200, "message": "Success", "data": {"user_certificates": [{
                  "service_type": "SMARTCA", %s "serial_number": "%s", "cert_data": "%s",
                  "chain_data": {"ca_cert": %s, "root_cert": null}, "transaction_id": "FINORA-CERT"}]}}
                """.formatted(statusField, serial, certData, chain);
    }

    /** Chứng thư tự ký sinh trong test với serial và thời hạn cho trước; không có khóa nào được commit. */
    private static java.security.cert.X509Certificate certificate(
            java.math.BigInteger serial,
            java.time.Instant notBefore,
            java.time.Instant notAfter
    ) throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        org.bouncycastle.asn1.x500.X500Name subject =
                new org.bouncycastle.asn1.x500.X500Name("CN=SmartCA Test, O=FINORA Test, C=VN");
        org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder builder =
                new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
                        subject, serial, java.util.Date.from(notBefore), java.util.Date.from(notAfter),
                        subject, keyPair.getPublic());
        return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(builder.build(
                new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                        .build(keyPair.getPrivate())));
    }

    private static String base64(java.security.cert.X509Certificate certificate) throws Exception {
        return java.util.Base64.getEncoder().encodeToString(certificate.getEncoded());
    }

    /** Base64 có xuống dòng kiểu MIME (\\r\\n trong JSON) để kiểm adapter chấp nhận cả dạng này. */
    private static String mime(java.security.cert.X509Certificate certificate) throws Exception {
        return java.util.Base64.getMimeEncoder().encodeToString(certificate.getEncoded()).replace("\r\n", "\\r\\n");
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
