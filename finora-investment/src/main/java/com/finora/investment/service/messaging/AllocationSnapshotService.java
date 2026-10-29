package com.finora.investment.service.messaging;

import com.finora.investment.domain.order.InvestmentCommitment;
import com.finora.investment.messaging.event.FundingAllocationEventData;

import java.util.List;

/** Canonical allocation luôn sắp theo commitment ID trước khi tính SHA-256. */
public interface AllocationSnapshotService {

    AllocationSnapshot snapshot(List<InvestmentCommitment> commitments);

    record AllocationSnapshot(String hash, List<FundingAllocationEventData> allocations) {
    }
}
