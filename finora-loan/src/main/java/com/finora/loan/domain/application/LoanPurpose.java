package com.finora.loan.domain.application;

import lombok.Getter;

@Getter
public enum LoanPurpose {
    DEBT_CONSOLIDATION("Hợp nhất các khoản nợ", "debt_consolidation", false),
    CREDIT_CARD("Thanh toán dư nợ thẻ tín dụng", "credit_card", false),
    HOME_IMPROVEMENT("Sửa chữa nhà", "home_improvement", false),
    MAJOR_PURCHASE("Mua sắm tài sản có giá trị", "major_purchase", false),
    MEDICAL("Chi phí y tế", "medical", false),
    CAR("Mua hoặc sửa chữa xe", "car", false),
    SMALL_BUSINESS("Vốn kinh doanh nhỏ", "small_business", false),
    MOVING("Chi phí chuyển nơi ở", "moving", false),
    VACATION("Du lịch", "vacation", false),
    EDUCATION("Chi phí giáo dục", "education", false),
    OTHER("Mục đích khác", "other", true);

    private final String label;
    private final String aiValue;
    private final boolean requiresDetail;

    LoanPurpose(String label, String aiValue, boolean requiresDetail) {
        this.label = label;
        this.aiValue = aiValue;
        this.requiresDetail = requiresDetail;
    }
}
