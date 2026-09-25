package com.finora.loan.integration.signature;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.loan.config.SignatureProviderProperties;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.exception.LoanBusinessException;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** VNPT SmartCA Web API v1 UAT; không nhận password/OTP của thuê bao. */
@Component
@ConditionalOnProperty(name = "finora.signature.provider", havingValue = "VNPT_SMART_CA")
@Slf4j
public class VnptSmartCaSignatureProvider implements SignatureProvider {

    private static final int SUCCESS = 200;
    private final RestClient restClient;
    private final SignatureProviderProperties.VnptSmartCa properties;

    public VnptSmartCaSignatureProvider(
            @Qualifier("smartCaRestClient") RestClient restClient,
            SignatureProviderProperties signatureProperties
    ) {
        this.restClient = restClient;
        this.properties = signatureProperties.vnptSmartCa();
    }

    @Override
    public SignatureSubmission submit(SignatureCommand command) {
        if (command.requestedMethod() != SignatureMethod.VNPT_SMART_CA) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Provider VNPT SmartCA chỉ hỗ trợ phương thức VNPT_SMART_CA");
        }
        validateCertificate(command.providerTransactionId());
        SmartCaResponse response = execute(
                "submit-signature",
                () -> restClient.post()
                        .uri("/v1/signatures/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new SignRequest(
                                properties.serviceProviderId(), properties.serviceProviderPassword(),
                                properties.sandboxUserId(), "FINORA " + command.contractNumber(),
                                List.of(new SignFile(command.pdfDocumentHash(), command.documentId(), "pdf", "hash")),
                                command.providerTransactionId(), properties.sandboxSerialNumber()))
                        .retrieve()
                        .body(SmartCaResponse.class));
        validateSuccess(response, "SMARTCA_SIGN_REJECTED");
        String transactionId = text(response.data(), "transaction_id");
        requireSame(command.providerTransactionId(), transactionId, "transaction_id");
        return SignatureSubmission.pending(transactionId, command.documentId(), response.message());
    }

    @Override
    public SignatureSubmission status(String providerTransactionId, String documentId) {
        try {
            SmartCaResponse response = execute(
                    "signature-status",
                    () -> restClient.post()
                            .uri("/v1/signatures/sign/{transactionId}/status", providerTransactionId)
                            .retrieve()
                            .body(SmartCaResponse.class));
            if (response != null && (Integer.valueOf(404).equals(response.statusCode())
                    || isNotFound(response.message()))) {
                return new SignatureSubmission(SignatureSubmissionStatus.NOT_FOUND,
                        providerTransactionId, documentId, null, null, response.message());
            }
            if (response != null && isRejected(response.message())) {
                return new SignatureSubmission(SignatureSubmissionStatus.REJECTED,
                        providerTransactionId, documentId, null, null, response.message());
            }
            validateSuccess(response, "SMARTCA_STATUS_REJECTED");
            requireSame(providerTransactionId, text(response.data(), "transaction_id"), "transaction_id");
            Object rawSignatures = response.data() == null ? null : response.data().get("signatures");
            if (rawSignatures instanceof List<?> signatures) {
                for (Object item : signatures) {
                    if (item instanceof java.util.Map<?, ?> signature
                            && documentId.equals(String.valueOf(signature.get("doc_id")))) {
                        String value = nullableText(signature.get("signature_value"));
                        if (value != null) {
                            return new SignatureSubmission(
                                    SignatureSubmissionStatus.COMPLETED, providerTransactionId, documentId,
                                    value, nullableText(signature.get("timestamp_signature")), response.message());
                        }
                    }
                }
            }
            return SignatureSubmission.pending(providerTransactionId, documentId, response.message());
        } catch (SmartCaIntegrationException exception) {
            if ("SMARTCA_HTTP_404".equals(exception.getCode())) {
                return new SignatureSubmission(SignatureSubmissionStatus.NOT_FOUND,
                        providerTransactionId, documentId, null, null, "NOT_FOUND");
            }
            throw exception;
        }
    }

    @Override
    public SignatureProviderType type() {
        return SignatureProviderType.VNPT_SMART_CA;
    }

    @Override
    public SignatureMethod method() {
        return SignatureMethod.VNPT_SMART_CA;
    }

    private void validateCertificate(String transactionId) {
        SmartCaResponse response = execute(
                "get-certificate",
                () -> restClient.post()
                        .uri("/v1/credentials/get_certificate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CertificateRequest(
                                properties.serviceProviderId(), properties.serviceProviderPassword(),
                                properties.sandboxUserId(), properties.sandboxSerialNumber(), transactionId + "-CERT"))
                        .retrieve()
                        .body(SmartCaResponse.class));
        validateSuccess(response, "SMARTCA_CERTIFICATE_REJECTED");
        Object rawCertificates = response.data() == null ? null : response.data().get("user_certificates");
        boolean valid = false;
        if (rawCertificates instanceof List<?> certificates) {
            valid = certificates.stream().anyMatch(item -> item instanceof java.util.Map<?, ?> certificate
                    && properties.sandboxSerialNumber().equalsIgnoreCase(
                    String.valueOf(certificate.get("serial_number")))
                    && isValidStatus(certificate.get("cert_status_code")));
        }
        if (!valid) {
            throw new SmartCaIntegrationException(
                    "SMARTCA_CERTIFICATE_NOT_VALID",
                    "Không tìm thấy chứng thư SmartCA UAT đang hoạt động",
                    false,
                    null);
        }
    }

    private boolean isValidStatus(Object value) {
        return value == null || "VALID".equalsIgnoreCase(String.valueOf(value));
    }

    private <T> T execute(String operation, ProviderCall<T> call) {
        StopWatch watch = new StopWatch();
        watch.start();
        try {
            return call.execute();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            throw new SmartCaIntegrationException(
                    "SMARTCA_HTTP_" + status,
                    "SmartCA từ chối yêu cầu với HTTP " + status,
                    status == 429 || status >= 500,
                    exception);
        } catch (ResourceAccessException exception) {
            throw new SmartCaIntegrationException(
                    "SMARTCA_UNAVAILABLE", "Không thể kết nối VNPT SmartCA", true, exception);
        } finally {
            watch.stop();
            log.info("Đã gọi dependency: dependency=VNPT_SMARTCA, operation={}, latencyMs={}",
                    operation, watch.getTotalTimeMillis());
        }
    }

    private void validateSuccess(SmartCaResponse response, String code) {
        if (response == null || response.statusCode() == null) {
            throw contractMismatch("SmartCA trả response thiếu status_code");
        }
        if (response.statusCode() != SUCCESS) {
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            throw new SmartCaIntegrationException(code, "SmartCA không chấp nhận yêu cầu", retryable, null);
        }
    }

    private boolean isRejected(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("reject") || normalized.contains("cancel")
                || normalized.contains("expired") || normalized.contains("denied");
    }

    private boolean isNotFound(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("not_found") || normalized.contains("not found")
                || normalized.contains("transaction_not_exist");
    }

    private String text(java.util.Map<String, Object> data, String field) {
        String value = data == null ? null : nullableText(data.get(field));
        if (value == null) {
            throw contractMismatch("SmartCA trả response thiếu " + field);
        }
        return value;
    }

    private String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private void requireSame(String expected, String actual, String field) {
        if (!expected.equals(actual)) {
            throw contractMismatch("SmartCA trả " + field + " không khớp yêu cầu");
        }
    }

    private SmartCaIntegrationException contractMismatch(String message) {
        return new SmartCaIntegrationException("SMARTCA_CONTRACT_MISMATCH", message, false, null);
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T execute();
    }

    private record CertificateRequest(
            @JsonProperty("sp_id") String serviceProviderId,
            @JsonProperty("sp_password") String serviceProviderPassword,
            @JsonProperty("user_id") String userId,
            @JsonProperty("serial_number") String serialNumber,
            @JsonProperty("transaction_id") String transactionId
    ) {
    }

    private record SignRequest(
            @JsonProperty("sp_id") String serviceProviderId,
            @JsonProperty("sp_password") String serviceProviderPassword,
            @JsonProperty("user_id") String userId,
            @JsonProperty("transaction_desc") String transactionDescription,
            @JsonProperty("sign_files") List<SignFile> signFiles,
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("serial_number") String serialNumber
    ) {
    }

    private record SignFile(
            @JsonProperty("data_to_be_signed") String dataToBeSigned,
            @JsonProperty("doc_id") String documentId,
            @JsonProperty("file_type") String fileType,
            @JsonProperty("sign_type") String signType
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SmartCaResponse(
            @JsonProperty("status_code") Integer statusCode,
            String message,
            java.util.Map<String, Object> data
    ) {
    }
}
