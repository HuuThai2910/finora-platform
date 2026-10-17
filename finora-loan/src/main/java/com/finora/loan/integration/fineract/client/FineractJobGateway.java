package com.finora.loan.integration.fineract.client;

import com.finora.loan.integration.fineract.contract.FineractJob;
import com.finora.loan.integration.fineract.contract.FineractJobRun;
import java.util.List;

/**
 * Đọc trạng thái job lập lịch của Fineract để giám sát job cuối ngày (W3E, D57). Chỉ có thao tác đọc; tài khoản API
 * của Loan cần quyền {@code READ_SCHEDULER} trên Fineract.
 */
public interface FineractJobGateway {

    /** {@code GET /jobs}: mọi job của tenant kèm lần chạy gần nhất. */
    List<FineractJob> listJobs();

    /** {@code GET /jobs/{jobId}/runhistory}: tối đa {@code limit} lần chạy gần nhất, mới trước. */
    List<FineractJobRun> recentRuns(long jobId, int limit);
}
