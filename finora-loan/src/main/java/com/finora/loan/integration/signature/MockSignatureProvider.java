package com.finora.loan.integration.signature;

import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.support.HashingService;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock giữ tương thích click-wrap hiện tại; evidence này tuyệt đối không được gọi là chữ ký số.
 *
 * <p>Với hợp đồng PAdES (R5), mock ký digest bằng khóa demo sinh lúc khởi động để PDF có chữ ký CMS thật mà trình đọc
 * chuẩn phân tích được; chứng thư tự ký ghi rõ "KHONG CO GIA TRI PHAP LY".</p>
 */
@Component
@ConditionalOnProperty(
        name = "finora.signature.provider",
        havingValue = "MOCK",
        matchIfMissing = true
)
public class MockSignatureProvider implements SignatureProvider {

    private final HashingService hashingService;
    private final DemoSigningCredential demoCredential;

    public MockSignatureProvider(HashingService hashingService) {
        this(hashingService, Clock.systemUTC());
    }

    @Autowired
    public MockSignatureProvider(HashingService hashingService, Clock clock) {
        this.hashingService = hashingService;
        this.demoCredential = DemoSigningCredential.generate(clock.instant());
    }

    @Override
    public SignatureSubmission submit(SignatureCommand command) {
        requireClickWrap(command.requestedMethod());
        String evidenceHash = hashingService.sha256(command);
        return new SignatureSubmission(
                SignatureSubmissionStatus.COMPLETED,
                "MOCK-" + evidenceHash.substring(0, 32),
                command.documentId(),
                evidenceHash,
                null,
                "MOCK_COMPLETED"
        );
    }

    @Override
    public SignatureSubmission status(String providerTransactionId, String documentId) {
        return new SignatureSubmission(
                SignatureSubmissionStatus.COMPLETED,
                providerTransactionId,
                documentId,
                hashingService.sha256(providerTransactionId + "|" + documentId),
                null,
                "MOCK_COMPLETED"
        );
    }

    @Override
    public SignatureProviderType type() {
        return SignatureProviderType.MOCK;
    }

    @Override
    public SignatureMethod method() {
        return SignatureMethod.CLICK_WRAP_MVP;
    }

    @Override
    public SigningCertificate signingCertificate(String signerId) {
        return demoCredential.signingCertificate();
    }

    @Override
    public SignatureSubmission submitDigest(DigestSignatureCommand command) {
        requireClickWrap(command.requestedMethod());
        byte[] signature = demoCredential.signDigest(HexFormat.of().parseHex(command.digestHex()));
        return new SignatureSubmission(
                SignatureSubmissionStatus.COMPLETED,
                command.providerTransactionId(),
                command.documentId(),
                Base64.getEncoder().encodeToString(signature),
                null,
                "MOCK_PADES_SIGNED"
        );
    }

    @Override
    public boolean completesSynchronously() {
        return true;
    }

    private void requireClickWrap(SignatureMethod requestedMethod) {
        if (requestedMethod != SignatureMethod.CLICK_WRAP_MVP) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Provider mock chỉ hỗ trợ xác nhận click-wrap"
            );
        }
    }
}
