package com.finora.loan.domain.decision;

/** Mã lý do chuẩn hóa để quyết định admin có thể audit và thống kê mà không đọc text tự do. */
public enum AdminDecisionReasonCode {
    POLICY_APPROVED(true),
    INSUFFICIENT_REPAYMENT_CAPACITY(false),
    IDENTITY_OR_KYC_NOT_ELIGIBLE(false),
    INCONSISTENT_DECLARED_INFORMATION(false),
    POLICY_NOT_SATISFIED(false),
    OTHER_MANUAL_REVIEW(false);

    private final boolean approval;

    AdminDecisionReasonCode(boolean approval) {
        this.approval = approval;
    }

    public boolean isApproval() {
        return approval;
    }
}
