package com.finora.loan.service.application;

import com.finora.loan.domain.application.BorrowerKycSnapshot;
import com.finora.loan.integration.profile.contract.BorrowerProfileResult;

/**
 * Chuyển giữa kết quả của provider hồ sơ (tầng integration) và snapshot lưu trên hồ sơ (tầng domain). Domain không được
 * phụ thuộc integration nên việc chuyển nằm ở tầng service.
 */
public final class BorrowerKycSnapshots {

    private BorrowerKycSnapshots() {
    }

    public static BorrowerKycSnapshot from(BorrowerProfileResult profile) {
        return BorrowerKycSnapshot.capture(
                profile.kycStatus(),
                profile.age(),
                profile.kycReference(),
                profile.kycVersion(),
                profile.incomeVerificationStatus(),
                profile.source(),
                profile.capturedAt());
    }

    /** Dựng lại input của policy điều kiện từ snapshot; ngày sinh không được lưu nên luôn {@code null}. */
    public static BorrowerProfileResult toProfile(String borrowerId, BorrowerKycSnapshot snapshot) {
        return new BorrowerProfileResult(
                borrowerId,
                null,
                snapshot.getAge(),
                snapshot.getKycStatus(),
                snapshot.getKycReference(),
                snapshot.getKycVersion(),
                snapshot.getIncomeVerificationStatus(),
                snapshot.getSource(),
                snapshot.getCapturedAt());
    }
}
