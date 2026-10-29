package com.finora.investment.service.transferdocument;

import com.finora.investment.config.TransferDocumentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Quét định kỳ lần khớp đã thanh toán để lập giấy xác nhận chuyển nhượng. Trạng thái nằm trong bảng nên service chết
 * giữa chừng thì lần quét sau làm tiếp; lần khớp đã thanh toán trước khi có tính năng này cũng được lập bù.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "finora.investment.transfer-document", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TransferDocumentWorker {

    private final TransferDocumentIssuer issuer;
    private final TransferDocumentProperties properties;

    @Scheduled(fixedDelayString = "${finora.investment.transfer-document.delay:10000}")
    public void run() {
        try {
            issuer.issueDue(properties.batchSize());
        } catch (RuntimeException failure) {
            log.error("Worker giấy xác nhận chuyển nhượng gặp lỗi: exceptionType={}", failure.getClass().getName());
        }
    }
}
