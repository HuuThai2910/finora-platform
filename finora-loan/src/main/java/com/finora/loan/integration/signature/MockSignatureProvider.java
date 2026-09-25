package com.finora.loan.integration.signature;

import com.finora.loan.domain.contract.SignatureMethod;
import com.finora.loan.domain.contract.SignatureProviderType;
import com.finora.loan.exception.LoanBusinessException;
import com.finora.loan.support.HashingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Mock giữ tương thích click-wrap hiện tại; evidence này tuyệt đối không được gọi là chữ ký số. */
@Component
@ConditionalOnProperty(
        name = "finora.signature.provider",
        havingValue = "MOCK",
        matchIfMissing = true
)
public class MockSignatureProvider implements SignatureProvider {

    private final HashingService hashingService;

    public MockSignatureProvider(HashingService hashingService) {
        this.hashingService = hashingService;
    }

    @Override
    public SignatureSubmission submit(SignatureCommand command) {
        if (command.requestedMethod() != SignatureMethod.CLICK_WRAP_MVP) {
            throw LoanBusinessException.badRequest(
                    "SIGNATURE_METHOD_PROVIDER_MISMATCH",
                    "Provider mock chỉ hỗ trợ xác nhận click-wrap"
            );
        }
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
}
