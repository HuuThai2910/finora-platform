package com.finora.loan.domain.collection;

public enum CollectionStage {
    EARLY_REMINDER,
    ATTENTION,
    NPL,
    INTENSIVE,
    LOSS;

    public static CollectionStage fromDaysPastDue(int days) {
        if (days <= 0) throw new IllegalArgumentException("DPD phải dương để mở hồ sơ thu hồi");
        if (days <= 9) return EARLY_REMINDER;
        if (days <= 90) return ATTENTION;
        if (days <= 180) return NPL;
        if (days <= 360) return INTENSIVE;
        return LOSS;
    }
}

