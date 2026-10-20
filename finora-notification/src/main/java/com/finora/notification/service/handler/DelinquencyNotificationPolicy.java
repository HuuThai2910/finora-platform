package com.finora.notification.service.handler;

import com.finora.notification.service.template.NotificationTemplate;
import java.util.Optional;

/**
 * Khi nào báo người vay về quá hạn (D61). {@code LoanDelinquencyChanged} được phát mỗi lần DPD đổi, tức mỗi ngày khi
 * đang quá hạn; chỉ báo khi đợt quá hạn mở/đóng hoặc nhóm nợ tăng, không báo mỗi ngày.
 * <p>
 * Nhóm nợ trong event là nhóm của đợt quá hạn (D29): trong một đợt nhóm không giảm khi trả bớt, nên "nhóm tăng" đúng là
 * chuyển giai đoạn thu hồi (Loan: {@code CollectionStage.fromDebtGroup}): 1→2 ở ngày 10, 2→3 (nợ xấu, DEFAULTED) ở ngày
 * 91, 3→4 ở ngày 181, 4→5 ở ngày 361. Một đợt tối đa sáu thông báo.
 * <pre>
 * DPD 0 → >0            : LOAN_OVERDUE (mở đợt; nếu nhảy thẳng lên nhóm ≥3 thì LOAN_DEFAULTED)
 * nhóm tăng, chạm 3     : LOAN_DEFAULTED
 * nhóm tăng khác        : OVERDUE_STAGE_CHANGED
 * DPD >0 → 0            : OVERDUE_CURED (đóng đợt; khoản DEFAULTED trở lại ACTIVE nếu còn dư nợ)
 * còn lại (DPD +1/-n)   : không báo
 * </pre>
 */
public final class DelinquencyNotificationPolicy {

    static final int BAD_DEBT_GROUP = 3;

    private DelinquencyNotificationPolicy() {
    }

    public static Optional<NotificationTemplate> decide(int previousDaysPastDue, int daysPastDue,
            int previousDebtGroup, int debtGroup) {
        boolean cured = previousDaysPastDue > 0 && daysPastDue == 0;
        if (cured) return Optional.of(NotificationTemplate.OVERDUE_CURED);
        if (daysPastDue <= 0) return Optional.empty();
        boolean opened = previousDaysPastDue == 0;
        boolean groupIncreased = debtGroup > previousDebtGroup;
        boolean becameBadDebt = debtGroup >= BAD_DEBT_GROUP && (previousDebtGroup < BAD_DEBT_GROUP || opened);
        if (becameBadDebt && (groupIncreased || opened)) return Optional.of(NotificationTemplate.LOAN_DEFAULTED);
        if (opened) return Optional.of(NotificationTemplate.LOAN_OVERDUE);
        if (groupIncreased) return Optional.of(NotificationTemplate.OVERDUE_STAGE_CHANGED);
        return Optional.empty();
    }

    /** Tên nhóm nợ nội bộ theo LOAN-SERVICING-POLICY §4 (benchmark demo, không phải kết luận CIC). */
    public static String stageLabel(int debtGroup) {
        return switch (debtGroup) {
            case 1 -> "đủ tiêu chuẩn";
            case 2 -> "cần chú ý";
            case 3 -> "dưới tiêu chuẩn";
            case 4 -> "nghi ngờ";
            case 5 -> "có khả năng mất vốn";
            default -> "chưa xác định";
        };
    }
}
