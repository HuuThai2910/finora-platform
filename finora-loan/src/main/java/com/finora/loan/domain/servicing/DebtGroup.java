package com.finora.loan.domain.servicing;

/**
 * Nhóm nợ nội bộ theo số ngày quá hạn (DPD), nguồn duy nhất cho đồng bộ servicing, hồ sơ thu hồi và thống kê.
 *
 * <p>Ngưỡng 9/90/180/360 ngày bám cách phân nhóm nợ năm nhóm quen thuộc của tổ chức tín dụng Việt Nam,
 * nhưng ở FINORA đây là policy nghiệp vụ demo, không phải kết luận pháp lý. Nhóm 3 đến 5 được coi là
 * nợ xấu (NPL). Mọi nơi cần nhóm nợ phải gọi lớp này để event, hồ sơ thu hồi và báo cáo không lệch nhau.</p>
 *
 * <p>D29: trong một đợt quá hạn (từ lúc quá hạn tới khi trả đủ phần quá hạn), nhóm nợ không giảm khi người vay chỉ trả
 * bớt; nhóm của đợt là nhóm cao nhất đã chạm ({@link #withinEpisode}). Khớp quy tắc khoản vay {@code DEFAULTED} chỉ hồi
 * phục khi trả đủ, và thực tiễn ngân hàng không chuyển nợ về nhóm thấp hơn khi khách hàng chưa trả đủ nợ quá hạn.</p>
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

    /**
     * Nhóm nợ trong một đợt quá hạn: không thấp hơn nhóm cao nhất đã chạm trong đợt.
     *
     * @param episodeGroup nhóm cao nhất của đợt đang mở (hồ sơ thu hồi {@code OPEN}); {@code null} khi không có đợt nào
     */
    public static int withinEpisode(Integer episodeGroup, int daysPastDue) {
        int byDays = fromDaysPastDue(daysPastDue);
        return episodeGroup == null ? byDays : Math.max(episodeGroup, byDays);
    }

    public static boolean isNonPerforming(int debtGroup) {
        return debtGroup >= FIRST_NON_PERFORMING;
    }
}
