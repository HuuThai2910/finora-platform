package com.finora.investment.service.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.messaging.event.FundingAllocationEventData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

/** Canonical allocation luôn sắp theo commitment ID trước khi tính SHA-256. */
@Service
public class AllocationSnapshotService {

    private final ObjectMapper objectMapper;

    public AllocationSnapshotService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AllocationSnapshot snapshot(List<InvestmentCommitment> commitments) {
        List<FundingAllocationEventData> allocations = commitments.stream()
                .sorted(java.util.Comparator.comparing(InvestmentCommitment::getId))
                .map(commitment -> new FundingAllocationEventData(
                        commitment.getId(),
                        commitment.getInvestorId(),
                        commitment.getAmount().toPlainString(),
                        commitment.getSharePercent().toPlainString(),
                        commitment.getPaymentHoldReference()))
                .toList();
        try {
            String json = objectMapper.writeValueAsString(allocations);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
            return new AllocationSnapshot(hash, allocations);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Không thể tạo allocation snapshot", exception);
        }
    }

    public record AllocationSnapshot(String hash, List<FundingAllocationEventData> allocations) {
    }
}
