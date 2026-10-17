package com.finora.loan.service.contract;

import com.finora.loan.config.PadesSignatureProperties;
import com.finora.loan.domain.contract.ContractPartyType;
import com.finora.loan.integration.signature.SignatureProvider;
import com.finora.loan.repository.contract.InFlightPdfSignature;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đối soát phiên ký SmartCA phía server (B47): người dùng xác nhận trên app SmartCA rồi không quay lại FINORA thì
 * chữ ký vẫn được thu về; phiên bỏ dở quá hạn được đóng để bên ký tiếp theo không bị chặn mãi.
 *
 * <p>Không cần lease: tra trạng thái là thao tác chỉ đọc phía VNPT, còn việc nhúng/đóng phiên chạy dưới khóa Contract
 * và kiểm lại trạng thái PREPARED, nên hai instance cùng xử lý một phiên chỉ có một bên ghi. MOCK ký trong một
 * transaction nên không bao giờ có phiên treo; worker bỏ qua.</p>
 */
@Component
@ConditionalOnProperty(
        name = "finora.signature.pades.worker-enabled",
        havingValue = "true",
        matchIfMissing = true
)
@RequiredArgsConstructor
@Slf4j
public class ContractPdfSignatureWorker {

    private final ContractPdfSignatureService pdfSignatureService;
    private final InvestorContractService investorContractService;
    private final LoanContractService loanContractService;
    private final SignatureProvider signatureProvider;
    private final PadesSignatureProperties properties;
    private final Clock clock;

    /** Không có transaction bao lượt quét; mỗi phiên đi qua service riêng với lời gọi mạng nằm ngoài transaction. */
    @Scheduled(fixedDelayString = "${finora.signature.pades.worker-delay:15000}")
    public void reconcileInFlightSignatures() {
        if (signatureProvider.completesSynchronously()) {
            return;
        }
        Instant now = clock.instant();
        for (InFlightPdfSignature item : pdfSignatureService.dueInFlight(properties.workerBatchSize())) {
            boolean sessionExpired = !item.preparedAt().plus(properties.signingSessionTimeout()).isAfter(now);
            try {
                if (item.signerType() == ContractPartyType.LENDER) {
                    investorContractService.reconcilePadesSignature(
                            item.contractNumber(), item.signerId(), sessionExpired);
                } else {
                    loanContractService.reconcilePadesSignature(item.contractNumber(), sessionExpired);
                }
            } catch (RuntimeException failure) {
                log.warn("Worker chưa đối soát được phiên ký PDF: signatureId={}, contractNumber={}, exceptionType={}",
                        item.signatureId(), item.contractNumber(), failure.getClass().getName());
            }
        }
    }
}
