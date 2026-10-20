package com.finora.loan.integration.signature;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.finora.loan.config.SignatureProviderProperties;
import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.exception.LoanBusinessException;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * VNPT SmartCA Web API v1 UAT; không nhận password/OTP của thuê bao.
 *
 * <p>Đặc tả VNPT (SP 769, bản 2.0 và tài liệu tích hợp 4.1): {@code get_certificate} trả {@code cert_data} là DER
 * base64 của chứng thư thuê bao cùng {@code chain_data.ca_cert/root_cert}; {@code sign} với {@code sign_type = hash}
 * nhận {@code data_to_be_signed} dạng hex; {@code status} trả {@code signature_value} base64. Giao dịch hết hạn sau
 * khoảng 300 giây ({@code expired_in = 299} trong phản hồi mẫu).</p>
 */
@Component
@ConditionalOnProperty(name = "finora.signature.provider", havingValue = "VNPT_SMART_CA")
@Slf4j
public class VnptSmartCaSignatureProvider implements SignatureProvider {

    private static final int SUCCESS = 200;
    /** Trạng thái chứng thư hoạt động: field cũ {@code cert_status_code} và field tiếng Việt {@code cert_status}. */
    private static final Set<String> ACTIVE_CERTIFICATE_STATUSES = Set.of("valid", "active", "đang hoạt động");

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
        requireSmartCaMethod(command.requestedMethod());
        requireActive(selectCertificate(command.providerTransactionId() + "-CERT"));
        return requestHashSignature(
                command.providerTransactionId(), command.documentId(), command.pdfDocumentHash(),
                "FINORA " + command.contractNumber());
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
                    if (item instanceof Map<?, ?> signature
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

    /**
     * Lấy và kiểm chứng thư thật của thuê bao (B46): serial phải đúng serial cấu hình, còn hiệu lực, khóa RSA dùng để
     * ký. Không log subject vì chứng thư cá nhân VN có thể chứa số định danh (UID=CMND/CCCD).
     */
    @Override
    public SigningCertificate signingCertificate(String signerId) {
        Map<?, ?> entry = selectCertificate("FINORA-CERT-" + UUID.randomUUID());
        requireActive(entry);
        X509Certificate certificate = decodeCertificate(entry.get("cert_data"), "cert_data");
        if (certificate == null) {
            throw contractMismatch("SmartCA không trả cert_data của chứng thư");
        }
        if (!normalizeSerial(certificate.getSerialNumber().toString(16))
                .equals(normalizeSerial(properties.sandboxSerialNumber()))) {
            throw contractMismatch("SmartCA trả chứng thư khác serial đã cấu hình");
        }
        try {
            certificate.checkValidity();
        } catch (CertificateExpiredException | CertificateNotYetValidException exception) {
            throw new SmartCaIntegrationException(
                    "SMARTCA_CERTIFICATE_NOT_VALID", "Chứng thư SmartCA UAT không còn trong thời hạn hiệu lực",
                    false, exception);
        }
        if (!"RSA".equalsIgnoreCase(certificate.getPublicKey().getAlgorithm())) {
            throw new SmartCaIntegrationException(
                    "SMARTCA_CERTIFICATE_UNSUPPORTED", "FINORA chỉ dựng CMS cho chứng thư khóa RSA", false, null);
        }
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null && !(keyUsage[0] || (keyUsage.length > 1 && keyUsage[1]))) {
            throw new SmartCaIntegrationException(
                    "SMARTCA_CERTIFICATE_UNSUPPORTED", "Chứng thư SmartCA không cho phép ký số", false, null);
        }
        List<X509Certificate> chain = new ArrayList<>();
        if (entry.get("chain_data") instanceof Map<?, ?> chainData) {
            X509Certificate caCertificate = decodeCertificate(chainData.get("ca_cert"), "ca_cert");
            if (caCertificate != null) {
                chain.add(caCertificate);
            }
            X509Certificate rootCertificate = decodeCertificate(chainData.get("root_cert"), "root_cert");
            if (rootCertificate != null) {
                chain.add(rootCertificate);
            }
        }
        return new SigningCertificate(certificate, chain);
    }

    /** PAdES: SmartCA ký hash của DER signed attributes; người ký xác nhận trên app nên kết quả luôn PENDING. */
    @Override
    public SignatureSubmission submitDigest(DigestSignatureCommand command) {
        requireSmartCaMethod(command.requestedMethod());
        return requestHashSignature(
                command.providerTransactionId(), command.documentId(), command.digestHex(), command.description());
    }

    @Override
    public boolean completesSynchronously() {
        return false;
    }

    private SignatureSubmission requestHashSignature(
            String transactionId,
            String documentId,
            String hashHex,
            String description
    ) {
        SmartCaResponse response = execute(
                "submit-signature",
                () -> restClient.post()
                        .uri("/v1/signatures/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new SignRequest(
                                properties.serviceProviderId(), properties.serviceProviderPassword(),
                                properties.sandboxUserId(), description,
                                List.of(new SignFile(hashHex, documentId, "pdf", "hash")),
                                transactionId, properties.sandboxSerialNumber()))
                        .retrieve()
                        .body(SmartCaResponse.class));
        validateSuccess(response, "SMARTCA_SIGN_REJECTED");
        String returnedTransactionId = text(response.data(), "transaction_id");
        requireSame(transactionId, returnedTransactionId, "transaction_id");
        return SignatureSubmission.pending(returnedTransactionId, documentId, response.message());
    }

    private void requireSmartCaMethod(SignatureMethod requestedMethod) {
        if (requestedMethod != SignatureMethod.VNPT_SMART_CA) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Provider VNPT SmartCA chỉ hỗ trợ phương thức VNPT_SMART_CA");
        }
    }

    private Map<?, ?> selectCertificate(String transactionId) {
        SmartCaResponse response = execute(
                "get-certificate",
                () -> restClient.post()
                        .uri("/v1/credentials/get_certificate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CertificateRequest(
                                properties.serviceProviderId(), properties.serviceProviderPassword(),
                                properties.sandboxUserId(), properties.sandboxSerialNumber(), transactionId))
                        .retrieve()
                        .body(SmartCaResponse.class));
        validateSuccess(response, "SMARTCA_CERTIFICATE_REJECTED");
        Object rawCertificates = response.data() == null ? null : response.data().get("user_certificates");
        if (rawCertificates instanceof List<?> certificates) {
            for (Object item : certificates) {
                if (item instanceof Map<?, ?> certificate
                        && normalizeSerial(properties.sandboxSerialNumber())
                        .equals(normalizeSerial(String.valueOf(certificate.get("serial_number"))))) {
                    return certificate;
                }
            }
        }
        throw notValidCertificate();
    }

    private void requireActive(Map<?, ?> certificate) {
        if (!isActiveStatus(certificate.get("cert_status_code")) || !isActiveStatus(certificate.get("cert_status"))) {
            throw notValidCertificate();
        }
    }

    /** Tài liệu 2.0 không có field trạng thái, tài liệu 4.1 dùng {@code cert_status = "Đang hoạt động"}. */
    private boolean isActiveStatus(Object value) {
        if (value == null) {
            return true;
        }
        String normalized = Normalizer.normalize(String.valueOf(value), Normalizer.Form.NFC)
                .trim().toLowerCase(Locale.ROOT);
        return ACTIVE_CERTIFICATE_STATUSES.contains(normalized);
    }

    private SmartCaIntegrationException notValidCertificate() {
        return new SmartCaIntegrationException(
                "SMARTCA_CERTIFICATE_NOT_VALID",
                "Không tìm thấy chứng thư SmartCA UAT đang hoạt động",
                false,
                null);
    }

    private X509Certificate decodeCertificate(Object value, String field) {
        String text = nullableText(value);
        if (text == null) {
            return null;
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(text);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (IllegalArgumentException | CertificateException | ClassCastException exception) {
            throw new SmartCaIntegrationException(
                    "SMARTCA_CONTRACT_MISMATCH", "SmartCA trả " + field + " không phải chứng thư X.509", false,
                    exception);
        }
    }

    /** So serial không phân biệt hoa thường và số 0 đứng đầu (BigInteger bỏ số 0 khi in hex). */
    private static String normalizeSerial(String serial) {
        if (serial == null || serial.isBlank()) {
            return "";
        }
        try {
            return new BigInteger(serial.trim(), 16).toString(16);
        } catch (NumberFormatException exception) {
            return serial.trim().toLowerCase(Locale.ROOT);
        }
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

    private String text(Map<String, Object> data, String field) {
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
            Map<String, Object> data
    ) {
    }
}
