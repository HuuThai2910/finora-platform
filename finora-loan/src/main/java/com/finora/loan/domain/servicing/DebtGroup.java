package com.finora.loan.domain.servicing;

/**
 * Nhóm nợ nội bộ theo số ngày quá hạn (DPD), nguồn duy nhất cho đồng bộ servicing, hồ sơ thu hồi và thống kê.
 *
 * <p>Ngưỡng 9/90/180/360 ngày bám cách phân nhóm nợ năm nhóm quen thuộc của tổ chức tín dụng Việt Nam,
 * nhưng ở FINORA đây là policy nghiệp vụ demo, không phải kết luận pháp lý. Nhóm 3 đến 5 được coi là
 * nợ xấu (NPL). Mọi nơi cần nhóm nợ phải gọi lớp này để event, hồ sơ thu hồi và báo cáo không lệch nhau.</p>
 */
public final class DebtGroup {

    public static final int FIRST = 1;
    public static final int LAST = 5;

    /** Nhóm nhỏ nhất bị tính là nợ xấu. */
    public static final int FIRST_NON_PERFORMING = 3;

    private DebtGroup() {
    }

    public static int fromDaysPastDue(int daysPastDue) {
        if (daysPastDue <= 9) return 1;
        if (daysPastDue <= 90) return 2;
        if (daysPastDue <= 180) return 3;
        if (daysPastDue <= 360) return 4;
        return 5;
    }

    public static boolean isNonPerforming(int debtGroup) {
        return debtGroup >= FIRST_NON_PERFORMING;
    }
}
