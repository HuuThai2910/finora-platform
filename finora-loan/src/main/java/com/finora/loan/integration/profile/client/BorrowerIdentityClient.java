package com.finora.loan.integration.profile.client;

import com.finora.loan.domain.contract.model.BorrowerIdentity;
import com.finora.loan.integration.profile.BorrowerIdentityUnavailableException;

/**
 * Họ tên và CCCD đã che của người vay ở finora-user, in lên hợp đồng vay khi lập.
 *
 * <p>Gọi bằng service account {@code finora-loan-client} (client role {@code user:identity:read_internal} của
 * {@code finora-user-client}) vì hợp đồng được lập trong consumer Kafka, không có token người vay. Bản {@code user} gọi
 * endpoint nội bộ của User; bản {@code mock} cho Loan chạy một mình trả danh tính rỗng.</p>
 */
public interface BorrowerIdentityClient {

    /**
     * @param borrowerId giá trị Loan lưu làm {@code borrower_id} (claim {@code user_id} của người vay)
     * @throws BorrowerIdentityUnavailableException khi chưa đọc được từ User; nơi gọi phải thử lại, không tự điền tên
     */
    BorrowerIdentity identity(String borrowerId);
}
