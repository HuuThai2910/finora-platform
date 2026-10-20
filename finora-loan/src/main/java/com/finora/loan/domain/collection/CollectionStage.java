package com.finora.loan.domain.collection;

public enum CollectionStage {
    EARLY_REMINDER,
    ATTENTION,
    NPL,
    INTENSIVE,
    LOSS;

    /** D29: mức thu hồi theo nhóm nợ của đợt quá hạn, để mức và nhóm trên hồ sơ không lệch khi nhóm được giữ. */
    public static CollectionStage fromDebtGroup(int debtGroup) {
        return switch (debtGroup) {
            case 1 -> EARLY_REMINDER;
            case 2 -> ATTENTION;
            case 3 -> NPL;
            case 4 -> INTENSIVE;
            case 5 -> LOSS;
            default -> throw new IllegalArgumentException("Nhóm nợ phải từ 1 đến 5");
        };
    }

    public static CollectionStage fromDaysPastDue(int days) {
        if (days <= 0) throw new IllegalArgumentException("DPD phải dương để mở hồ sơ thu hồi");
        if (days <= 9) return EARLY_REMINDER;
        if (days <= 90) return ATTENTION;
        if (days <= 180) return NPL;
        if (days <= 360) return INTENSIVE;
        return LOSS;
    }
}

