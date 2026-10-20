package com.finora.loan.integration.profile.provider;

import com.finora.loan.integration.profile.contract.BorrowerProfileResult;
import java.time.LocalDate;

/**
 * Nguồn trạng thái eKYC và tuổi của người vay (W2-KYC).
 *
 * <p>Loan gọi boundary này đúng một lần khi người vay nộp hồ sơ, trong chính request của người vay, rồi lưu kết quả làm
 * snapshot trên hồ sơ. Worker chấm điểm chạy nền dùng snapshot đó, không gọi lại. Provider {@code user} chuyển tiếp token
 * của người vay sang User nên chỉ chạy được trong request; provider {@code mock} chỉ dùng cho test và E2E.</p>
 */
public interface BorrowerProfileProvider {

    /**
     * @throws BorrowerProfileUnavailableException khi nguồn hồ sơ không trả lời được hoặc trả dữ liệu không dùng được;
     *                                             không được đoán trạng thái eKYC thay cho nguồn
     */
    BorrowerProfileResult getBorrowerProfile(String borrowerId, LocalDate asOf);

    /**
     * Provider đọc được hồ sơ khi không có request của người vay (worker). Chỉ dùng cho hồ sơ nộp trước khi có snapshot;
     * provider cần token của người vay trả {@code false}.
     */
    default boolean supportsBackgroundLookup() {
        return true;
    }
}
